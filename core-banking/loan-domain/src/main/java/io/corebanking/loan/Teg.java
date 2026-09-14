package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Taux effectif global d'un credit, et ce qui le compose.
 *
 * @param amountReceived  somme reellement mise a disposition : le capital, diminue des frais
 *                        preleves au deblocage
 * @param totalRepaid     somme de toutes les echeances, assurance, frais et taxes compris
 * @param method          methode d'annualisation employee ; le chiffre n'a pas de sens sans elle
 */
public record Teg(
    BigDecimal periodicRatePercent,
    BigDecimal annualRatePercent,
    RateAnnualisation method,
    Money amountReceived,
    Money totalRepaid) {

    public Teg {
        Objects.requireNonNull(periodicRatePercent, "periodicRatePercent");
        Objects.requireNonNull(annualRatePercent, "annualRatePercent");
        Objects.requireNonNull(method, "method");
    }

    /** Cout total du credit : ce que l'emprunteur paie en plus de ce qu'il a recu. */
    public Money totalCost() {
        return totalRepaid.minus(amountReceived);
    }

    /** Vrai si le taux depasse le plafond donne. Un plafond nul ou negatif ne plafonne rien. */
    public boolean exceeds(BigDecimal ceilingPercent) {
        return ceilingPercent != null && ceilingPercent.signum() > 0
               && annualRatePercent.compareTo(ceilingPercent) > 0;
    }

    @Override
    public String toString() {
        return annualRatePercent.toPlainString() + " % (" + method + ")";
    }
}
