package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.util.Objects;

/**
 * Calcul de la provision d'un credit.
 *
 * <p>Fonction pure : l'encours et les garanties lui sont fournis constates.
 */
public final class Provisioning {

    private Provisioning() {}

    /**
     * Provision d'un credit.
     *
     * @param exposure   encours porte par la banque : capital restant du et creances accessoires
     *                   encore ouvertes
     * @param collateral valeur des garanties, deja ponderee de leur quotite d'eligibilite
     */
    public static Provision compute(Money exposure, Money collateral, RiskBucket bucket) {
        Objects.requireNonNull(bucket, "bucket");
        Money zero = Money.zero(exposure.currency());
        Money eligible = collateral == null ? zero : collateral;
        if (eligible.isNegative()) {
            throw new IllegalArgumentException("Garantie negative : " + eligible);
        }
        // Une garantie ne couvre pas au-dela de ce qu'elle garantit. Sans ce plafond, une surete
        // surevaluee produirait une assiette negative, donc une provision negative, donc une
        // reprise de provision sur un credit en souffrance.
        Money retained = eligible.isGreaterThan(exposure) ? exposure : eligible;
        Money base = exposure.minus(retained);
        if (base.isNegative()) {
            base = zero;
        }
        Money amount = base.times(bucket.provisionRatePercent().movePointLeft(2)).roundToCurrency();
        return new Provision(exposure, retained, base, bucket.provisionRatePercent(), amount);
    }

    /**
     * Provision d'un credit, telle qu'elle est arretee.
     *
     * @param retainedCollateral garantie effectivement retenue, plafonnee a l'encours
     */
    public record Provision(
        Money exposure,
        Money retainedCollateral,
        Money base,
        java.math.BigDecimal ratePercent,
        Money amount) {

        /** Variation a comptabiliser par rapport a la provision deja constituee. */
        public Money deltaFrom(Money alreadyProvisioned) {
            return amount.minus(alreadyProvisioned);
        }
    }
}
