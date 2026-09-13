package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.error.UnbalancedEntryException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gardien des invariants d'ecriture.
 *
 * <p>C'est le seul point de construction d'une {@link ValidatedEntry}. Une commande qui franchit
 * ce validateur est comptabilisable ; une commande rejetee ne laisse aucune trace.
 */
public final class EntryValidator {

    /** Nombre minimal de lignes : une ecriture a partie double en a au moins deux. */
    private static final int MIN_LINES = 2;

    private EntryValidator() {}

    public static ValidatedEntry validate(PostingCommand command, PostingContext context) {
        if (command.lines().size() < MIN_LINES) {
            throw new InvalidPostingException(
                "Ecriture a " + command.lines().size() + " ligne(s) : minimum " + MIN_LINES
                + " en partie double.");
        }

        List<ValidatedLine> validated = new ArrayList<>(command.lines().size());
        for (PostingLine line : command.lines()) {
            validated.add(validateLine(line, command, context));
        }

        requireBalancedPerCurrency(validated);
        requireBalancedInFunctionalCurrency(validated, context.functionalCurrency());

        return new ValidatedEntry(command, validated, context.functionalCurrency());
    }

    private static ValidatedLine validateLine(PostingLine line, PostingCommand command,
                                              PostingContext context) {
        Account account = context.accounts().get(line.accountId());
        if (account == null) {
            throw new InvalidPostingException("Compte inconnu : " + line.accountId());
        }
        if (!account.legalEntityId().equals(command.legalEntityId())) {
            throw new InvalidPostingException(
                "Cloisonnement des entites : le compte " + account.code()
                + " n'appartient pas a l'entite " + command.legalEntityId());
        }
        if (!account.postable()) {
            throw new InvalidPostingException(
                "Compte non imputable (compte de regroupement) : " + account.code());
        }
        if (!account.status().acceptsPosting()) {
            throw new InvalidPostingException(
                "Compte " + account.code() + " au statut " + account.status()
                + " : aucune imputation possible.");
        }
        if (!account.currency().equals(line.amount().currency())) {
            throw new InvalidPostingException(
                "Devise de ligne " + line.amount().currency() + " incompatible avec le compte "
                + account.code() + " tenu en " + account.currency());
        }
        if (!line.amount().isBookable()) {
            throw new InvalidPostingException(
                "Montant " + line.amount() + " non comptabilisable : la devise "
                + account.currency() + " admet " + account.currency().scale() + " decimale(s). "
                + "Arrondir avant comptabilisation.");
        }
        return new ValidatedLine(line, account, functionalAmountOf(line, account, context));
    }

    /**
     * Contre-valeur dans la devise de tenue de compte, conservee a l'echelle interne.
     *
     * <p>Elle n'est pas arrondie a l'echelle de la devise fonctionnelle : arrondir chaque ligne
     * independamment romprait l'equilibre en contre-valeur. Un schema comptable qui ne tombe pas
     * juste doit donc porter explicitement sa ligne d'ecart d'arrondi, plutot que de laisser le
     * ledger absorber silencieusement la difference.
     */
    private static Money functionalAmountOf(PostingLine line, Account account,
                                            PostingContext context) {
        CurrencyRef functional = context.functionalCurrency();
        boolean sameCurrency = account.currency().equals(functional);

        if (sameCurrency) {
            if (line.fxRate() != null && line.fxRate().compareTo(BigDecimal.ONE) != 0) {
                throw new InvalidPostingException(
                    "Cours de change " + line.fxRate() + " sur une ligne deja libellee en "
                    + functional + " : aucune conversion n'est attendue.");
            }
            return Money.of(line.amount().amount(), functional);
        }
        if (line.fxRate() == null) {
            throw new InvalidPostingException(
                "Cours de change absent sur une ligne en " + account.currency()
                + " alors que l'entite tient ses comptes en " + functional);
        }
        return Money.of(line.amount().amount(), functional).times(line.fxRate());
    }

    /** Invariant central : dans chaque devise, la somme des debits egale celle des credits. */
    private static void requireBalancedPerCurrency(List<ValidatedLine> lines) {
        Map<String, Money> imbalanceByCurrency = new LinkedHashMap<>();
        for (ValidatedLine v : lines) {
            Money amount = v.line().amount();
            String code = amount.currency().code();
            Money delta = v.line().direction() == Direction.DEBIT ? amount : amount.negate();
            imbalanceByCurrency.merge(code, delta, Money::plus);
        }
        Map<String, Money> violations = new LinkedHashMap<>();
        imbalanceByCurrency.forEach((code, imbalance) -> {
            if (!imbalance.isZero()) {
                violations.put(code, imbalance);
            }
        });
        if (!violations.isEmpty()) {
            throw new UnbalancedEntryException(violations);
        }
    }

    /**
     * Second invariant d'equilibre, sur la contre-valeur toutes devises confondues.
     *
     * <p>Une fois l'equilibre par devise acquis, ce second controle detecte une seule chose, mais
     * elle est reelle : <b>des cours incoherents entre lignes d'une meme devise</b> au sein de la
     * meme ecriture. Un schema comptable qui applique le cours acheteur a la ligne client et le
     * cours de reference a la ligne de position produit une ecriture equilibree en EUR, equilibree
     * en XOF, et pourtant fausse : la difference est une perte ou un gain de change non
     * comptabilise. Elle apparait ici.
     *
     * <p><b>Ce que ce controle n'attrape pas.</b> Un cours errone mais applique <i>uniformement</i>
     * a toutes les lignes d'une devise : les contre-valeurs se compensent deux a deux, quel que
     * soit ce cours. Seule la confrontation au cours de reference — table des cours, tolerances et
     * marges — le detecte. C'est un controle distinct, du ressort du referentiel, et non du ledger.
     *
     * <p>La conversion directe a deux lignes, elle, ne parvient jamais jusqu'ici : elle est
     * desequilibree dans chaque devise et rejetee par le controle precedent.
     */
    private static void requireBalancedInFunctionalCurrency(List<ValidatedLine> lines,
                                                            CurrencyRef functionalCurrency) {
        Money imbalance = Money.of(BigDecimal.ZERO, functionalCurrency);
        for (ValidatedLine v : lines) {
            Money delta = v.line().direction() == Direction.DEBIT
                ? v.functionalAmount()
                : v.functionalAmount().negate();
            imbalance = imbalance.plus(delta);
        }
        if (!imbalance.isZero()) {
            throw new UnbalancedEntryException(
                Map.of(functionalCurrency.code() + " (contre-valeur)", imbalance));
        }
    }
}
