package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.RateSchedule;
import java.util.Objects;
import java.util.UUID;

/**
 * Conditions d'interet applicables a un compte.
 *
 * <p>En production, ces elements sont resolus depuis la product factory a la date de valeur
 * traitee, et non a la date du jour : un recalcul retroactif doit appliquer le bareme en vigueur a
 * l'epoque, sans quoi il produirait un montant different de l'original et l'arrete cesserait
 * d'etre reproductible.
 *
 * @param debitAccount  compte impute au debit de l'ecriture d'interets couru. Pour des interets
 *                      crediteurs sur depots : le compte de charges d'interets.
 * @param creditAccount compte impute au credit. Pour des interets crediteurs sur depots : le
 *                      compte d'interets courus non echus, au passif.
 */
public record InterestTerms(
    RateSchedule rates,
    DayCountConvention dayCount,
    AccrualSide side,
    UUID debitAccount,
    UUID creditAccount) {

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
}
