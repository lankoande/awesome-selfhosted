package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.Direction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Complete une ecriture par ses lignes de liaison, pour qu'elle soit equilibree agence par
 * agence — le treizieme invariant.
 *
 * <p>Schema « via le siege » : pour chaque agence dont les lignes ne s'equilibrent pas, une
 * ligne dans ses livres sur son compte de liaison, qui l'equilibre, et la ligne miroir dans les
 * livres du siege sur le meme compte. Le siege est equilibre par construction : l'ecriture
 * l'est pour l'entite, et la somme des nets des agences est l'oppose du sien.
 *
 * <p>Les lignes de liaison portent la date comptable en date de valeur — un compte de liaison ne
 * porte pas d'interets — et, en devise etrangere, la contre-valeur exacte du net qu'elles
 * compensent, pour que l'equilibre par agence tienne aussi en contre-valeur.
 */
public final class InterbranchBridging {

    private InterbranchBridging() {}

    /** Compte de liaison d'une agence, dans une devise. Absent : refus, jamais un repli. */
    @FunctionalInterface
    public interface LiaisonAccounts {
        Account liaisonAccount(UUID branchId, CurrencyRef currency);
    }

    private record Key(UUID branchId, String currency) {}

    public static ValidatedEntry complete(ValidatedEntry entry, PostingContext context,
                                          LiaisonAccounts liaison) {
        UUID headOffice = context.headOfficeId();
        if (headOffice == null) {
            return entry;                     // aucune agence : rien a completer
        }
        List<ValidatedLine> lines = new ArrayList<>(entry.lines());

        // Premiere passe : le net de chaque agence par devise, et sa contre-valeur.
        Map<Key, Money> net = new LinkedHashMap<>();
        Map<Key, Money> netFunctional = new LinkedHashMap<>();
        Map<String, CurrencyRef> currencies = new LinkedHashMap<>();
        for (ValidatedLine v : entry.lines()) {
            CurrencyRef currency = v.line().amount().currency();
            currencies.put(currency.code(), currency);
            Key key = new Key(v.branchId(), currency.code());
            net.merge(key, v.debitSigned(), Money::plus);
            netFunctional.merge(key, v.debitSignedFunctional(), Money::plus);
        }
        for (Map.Entry<Key, Money> e : net.entrySet()) {
            UUID branch = e.getKey().branchId();
            if (e.getValue().isZero() || branch == null || branch.equals(headOffice)) {
                continue;
            }
            CurrencyRef currency = currencies.get(e.getKey().currency());
            bridge(lines, entry, context, liaison, branch, headOffice, currency, e.getValue(),
                   netFunctional.get(e.getKey()));
        }

        // Seconde passe : un residu en contre-valeur seule — deux cours differents dans la meme
        // devise pour la meme agence — se compense en devise de tenue de compte.
        Map<UUID, Money> residual = new LinkedHashMap<>();
        for (ValidatedLine v : lines) {
            if (v.branchId() != null && !v.branchId().equals(headOffice)) {
                residual.merge(v.branchId(), v.debitSignedFunctional(), Money::plus);
            }
        }
        for (Map.Entry<UUID, Money> e : residual.entrySet()) {
            if (!e.getValue().isZero()) {
                bridge(lines, entry, context, liaison, e.getKey(), headOffice,
                       context.functionalCurrency(), e.getValue(), e.getValue());
            }
        }

        ValidatedEntry completed = new ValidatedEntry(entry.command(), lines,
                                                      entry.functionalCurrency(),
                                                      entry.operationBranchId());
        EntryValidator.requireBalancedPerBranch(completed.lines());
        return completed;
    }

    private static void bridge(List<ValidatedLine> lines, ValidatedEntry entry,
                               PostingContext context, LiaisonAccounts liaison, UUID branch,
                               UUID headOffice, CurrencyRef currency, Money net,
                               Money netFunctional) {
        Account account = liaison.liaisonAccount(branch, currency);
        Direction inBranch = net.isPositive() ? Direction.CREDIT : Direction.DEBIT;
        Money amount = net.abs();
        Money functionalAmount = netFunctional.abs();
        BigDecimal fxRate = currency.equals(context.functionalCurrency()) ? null
            : functionalAmount.amount().divide(amount.amount(), 10, RoundingMode.HALF_EVEN);
        String label = "Liaison agence " + branch + " / siege";
        java.time.LocalDate bookingDate = entry.command().bookingDate();

        PostingLine inBranchBooks = new PostingLine(account.id(), inBranch, amount, bookingDate,
                                                    label, fxRate, branch);
        PostingLine inHeadOfficeBooks = new PostingLine(account.id(), inBranch.opposite(), amount,
                                                        bookingDate, label, fxRate, headOffice);
        lines.add(new ValidatedLine(inBranchBooks, account, functionalAmount, branch,
                                    LineKind.LIAISON));
        lines.add(new ValidatedLine(inHeadOfficeBooks, account, functionalAmount, headOffice,
                                    LineKind.LIAISON));
    }
}
