package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.RateSchedule;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Conditions d'interet applicables a un compte, pour un cote.
 *
 * <p>En production, ces elements sont resolus depuis la product factory a la date de valeur
 * traitee, et non a la date du jour : un recalcul retroactif doit appliquer le bareme en vigueur a
 * l'epoque, sans quoi il produirait un montant different de l'original et l'arrete cesserait
 * d'etre reproductible.
 *
 * @param debitAccount  compte impute au debit de l'ecriture d'interets courus. Pour des interets
 *                      crediteurs sur depots : le compte de charges d'interets. Pour des agios : le
 *                      compte d'interets courus a recevoir, a l'actif.
 * @param creditAccount compte impute au credit. Pour des interets crediteurs sur depots : le
 *                      compte d'interets courus non echus, au passif. Pour des agios : le produit
 *                      d'interets sur decouverts.
 * @param settlement    conditions de reglement des courus, nulles si le produit n'en prevoit pas
 */
public record InterestTerms(
    RateSchedule rates,
    DayCountConvention dayCount,
    AccrualSide side,
    UUID debitAccount,
    UUID creditAccount,
    SettlementTerms settlement) {

    public InterestTerms {
        Objects.requireNonNull(rates, "rates");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(debitAccount, "debitAccount");
        Objects.requireNonNull(creditAccount, "creditAccount");
        if (debitAccount.equals(creditAccount)) {
            throw new IllegalArgumentException(
                "Les comptes de contrepartie des interets sont identiques : l'ecriture serait "
                + "equilibree mais sans effet.");
        }
    }

    public InterestTerms(RateSchedule rates, DayCountConvention dayCount, AccrualSide side,
                         UUID debitAccount, UUID creditAccount) {
        this(rates, dayCount, side, debitAccount, creditAccount, null);
    }

    public InterestTerms withSettlement(SettlementTerms terms) {
        return new InterestTerms(rates, dayCount, side, debitAccount, creditAccount, terms);
    }

    public Optional<SettlementTerms> settlementTerms() {
        return Optional.ofNullable(settlement);
    }

    /**
     * Compte de courus : celui dont le solde est le sous-livre des interets calcules et non
     * regles. Au passif pour des interets crediteurs, a l'actif pour des agios.
     */
    public UUID accruedAccount() {
        return side == AccrualSide.CREDITOR ? creditAccount : debitAccount;
    }
}
