package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.error.UnbalancedEntryException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        boolean offBalance = requireOneWorld(validated);
        UUID operationBranch = operationBranchOf(validated, command, context);
        validated = withBranches(validated, operationBranch);

        requireBalancedPerCurrency(validated);
        requireBalancedInFunctionalCurrency(validated, context.functionalCurrency());

        return new ValidatedEntry(command, validated, context.functionalCurrency(), operationBranch,
                                  offBalance);
    }

    /**
     * Bilan et hors bilan ne se melangent pas dans une ecriture. Un engagement par signature
     * s'inscrit entre comptes de hors bilan, avec sa contrepartie de hors bilan, comme le veut le
     * plan comptable bancaire ; une ecriture qui mettrait en jeu un compte de bilan et un compte
     * de hors bilan fausserait les deux etats a la fois, et aucun des deux ne s'equilibrerait.
     *
     * @return vrai si l'ecriture est de hors bilan
     */
    private static boolean requireOneWorld(List<ValidatedLine> lines) {
        boolean onBalance = false;
        boolean offBalance = false;
        for (ValidatedLine v : lines) {
            if (v.account().nature() == AccountNature.OFF_BALANCE_SHEET) {
                offBalance = true;
            } else {
                onBalance = true;
            }
        }
        if (onBalance && offBalance) {
            throw new InvalidPostingException(
                "Une ecriture ne melange pas le bilan et le hors bilan : un engagement s'inscrit "
                + "entre comptes de hors bilan, avec sa contrepartie de hors bilan.");
        }
        return offBalance;
    }

    /**
     * Agence de l'operation : celle de la commande ; sinon l'agence unique des comptes a agence
     * de l'ecriture, s'il n'y en a qu'une ; sinon le siege. C'est l'agence comptable des lignes
     * sur comptes generaux qui n'en precisent pas.
     */
    private static UUID operationBranchOf(List<ValidatedLine> lines, PostingCommand command,
                                          PostingContext context) {
        if (command.branchId() != null) {
            return command.branchId();
        }
        Set<UUID> branched = new LinkedHashSet<>();
        for (ValidatedLine v : lines) {
            if (v.account().hasBranch()) {
                branched.add(v.account().branchId());
            }
        }
        return branched.size() == 1 ? branched.iterator().next() : context.headOfficeId();
    }

    /**
     * Agence comptable de chaque ligne. Un compte a agence impose la sienne — une valeur
     * contraire est une erreur, pas un choix ; un compte general prend l'agence que la ligne
     * precise, a defaut celle de l'operation.
     */
    private static List<ValidatedLine> withBranches(List<ValidatedLine> lines, UUID operationBranch) {
        List<ValidatedLine> resolved = new ArrayList<>(lines.size());
        for (ValidatedLine v : lines) {
            UUID own = v.account().branchId();
            UUID given = v.line().branchId();
            UUID branch;
            if (own != null) {
                if (given != null && !given.equals(own)) {
                    throw new InvalidPostingException(
                        "La ligne sur le compte " + v.account().code() + " porte l'agence " + given
                        + " alors que le compte releve de l'agence " + own + " : l'agence d'un "
                        + "compte client ou interne ne se choisit pas a l'ecriture.");
                }
                branch = own;
            } else {
                branch = given != null ? given : operationBranch;
            }
            resolved.add(new ValidatedLine(v.line(), v.account(), v.functionalAmount(), branch,
                                           v.kind()));
        }
        return resolved;
    }

    /**
     * Equilibre par agence, par devise et en contre-valeur : le treizieme invariant. Verifie
     * apres que le service d'imputation a complete l'ecriture par ses lignes de liaison.
     */
    public static void requireBalancedPerBranch(List<ValidatedLine> lines) {
        Map<String, Money> violations = new LinkedHashMap<>();
        Map<String, Money> byBranchAndCurrency = new LinkedHashMap<>();
        Map<String, Money> functionalByBranch = new LinkedHashMap<>();
        for (ValidatedLine v : lines) {
            String branch = String.valueOf(v.branchId());
            byBranchAndCurrency.merge(v.line().amount().currency().code() + " @ agence " + branch,
                                      v.debitSigned(), Money::plus);
            functionalByBranch.merge(v.functionalAmount().currency().code()
                                     + " (contre-valeur) @ agence " + branch,
                                     v.debitSignedFunctional(), Money::plus);
        }
        byBranchAndCurrency.forEach((key, imbalance) -> {
            if (!imbalance.isZero()) {
                violations.put(key, imbalance);
            }
        });
        functionalByBranch.forEach((key, imbalance) -> {
            if (!imbalance.isZero()) {
                violations.put(key, imbalance);
            }
        });
        if (!violations.isEmpty()) {
            throw new UnbalancedEntryException(violations);
        }
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
        LineKind kind = context.liaisonAccountIds().contains(account.id()) ? LineKind.LIAISON
                                                                          : LineKind.BUSINESS;
        return new ValidatedLine(line, account, functionalAmountOf(line, account, context), null,
                                 kind);
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
