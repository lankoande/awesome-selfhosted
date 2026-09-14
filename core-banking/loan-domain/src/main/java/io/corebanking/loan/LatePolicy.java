package io.corebanking.loan;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Regime applique a une echeance impayee.
 *
 * <p>Deux prelevements de nature differente, et il faut les tenir separes : l'<b>interet de
 * retard</b> court chaque jour sur l'impaye, la <b>penalite</b> se percoit une fois par echeance.
 * Un moteur qui les confond facture soit une penalite quotidienne, soit aucun interet de retard.
 *
 * @param graceDays        franchise avant que le retard ne produise quoi que ce soit
 * @param maxRatePercent   taux maximal admis, cumul du taux nominal et du taux de retard. Ce n'est
 *                         pas le controle du taux d'usure — celui-ci porte sur le TEG et releve de
 *                         l'octroi — mais un garde-fou contre un parametrage aberrant.
 */
public record LatePolicy(
    CurrencyRef currency,
    BigDecimal lateInterestRatePercent,
    LateInterestBasis basis,
    DayCountConvention dayCount,
    int graceDays,
    PenaltyMode penaltyMode,
    Money penaltyAmount,
    BigDecimal penaltyRatePercent,
    Money penaltyFloor,
    Money penaltyCap,
    BigDecimal maxRatePercent) {

    public LatePolicy {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(penaltyMode, "penaltyMode");
        lateInterestRatePercent = nonNegative(lateInterestRatePercent, "taux d'interet de retard");
        penaltyRatePercent = nonNegative(penaltyRatePercent, "taux de penalite");
        maxRatePercent = nonNegative(maxRatePercent, "taux maximal");

        require(graceDays >= 0, "franchise negative : " + graceDays);
        switch (penaltyMode) {
            case NONE -> { }
            case FLAT_PER_INSTALMENT -> {
                require(penaltyAmount != null, "le forfait de penalite n'est pas parametre");
                require(!penaltyAmount.isNegative(), "forfait de penalite negatif");
                requireBookable(penaltyAmount, "le forfait de penalite");
            }
            case PERCENT_OF_OVERDUE ->
                require(penaltyRatePercent.signum() > 0,
                        "la penalite proportionnelle n'a pas de taux");
        }
        if (penaltyFloor != null) {
            requireBookable(penaltyFloor, "le plancher de penalite");
        }
        if (penaltyCap != null) {
            requireBookable(penaltyCap, "le plafond de penalite");
        }
        if (penaltyFloor != null && penaltyCap != null) {
            require(!penaltyFloor.isGreaterThan(penaltyCap),
                    "plancher de penalite " + penaltyFloor + " superieur au plafond " + penaltyCap);
        }
    }

    /**
     * Verifie que le cumul du taux nominal et du taux de retard reste admissible.
     *
     * <p>Appele au moment ou le contrat est connu, et non a la construction : le taux nominal
     * appartient au contrat, la politique de retard au produit.
     */
    public void requireCompatibleWith(BigDecimal nominalRatePercent) {
        if (maxRatePercent.signum() == 0) {
            return;
        }
        BigDecimal total = nominalRatePercent.add(lateInterestRatePercent);
        if (total.compareTo(maxRatePercent) > 0) {
            throw new InvalidLatePolicyException(
                "le cumul du taux nominal (" + nominalRatePercent + " %) et du taux de retard ("
                + lateInterestRatePercent + " %) atteint " + total + " %, au-dela du maximum admis "
                + maxRatePercent + " %");
        }
    }

    public boolean accruesInterest() {
        return lateInterestRatePercent.signum() > 0;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        BigDecimal actual = value == null ? BigDecimal.ZERO : value;
        require(actual.signum() >= 0, label + " negatif : " + actual);
        return actual;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new InvalidLatePolicyException(detail);
        }
    }

    private static void requireBookable(Money amount, String what) {
        require(amount.isBookable(),
                what + " " + amount + " n'est pas imputable en " + amount.currency());
    }

    /** Regime de retard refuse. */
    public static class InvalidLatePolicyException extends RuntimeException {
        public InvalidLatePolicyException(String detail) {
            super("Regime de retard : " + detail + ".");
        }
    }
}
