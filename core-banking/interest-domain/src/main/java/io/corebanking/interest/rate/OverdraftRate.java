package io.corebanking.interest.rate;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Taux d'agios a deux niveaux : dans l'autorisation, et au-dela.
 *
 * <p>Un decouvert autorise se paie au taux convenu ; le depassement de l'autorisation se paie plus
 * cher. Les deux parts sont calculees separement, chacune sur son assiette, et additionnees : un
 * taux moyen applique au solde entier introduirait un arrondi la ou la somme des deux parts est
 * exacte. L'autorisation est propre au compte — elle vient de la table des autorisations, ou du
 * plafond par defaut du produit — et peut changer d'un jour a l'autre.
 *
 * @param limit             autorisation de decouvert, en valeur absolue, dans la devise du compte
 * @param ratePercent       taux annuel dans l'autorisation
 * @param excessRatePercent taux annuel du depassement
 */
public record OverdraftRate(BigDecimal limit, BigDecimal ratePercent, BigDecimal excessRatePercent)
    implements RateSchedule {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public OverdraftRate {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(ratePercent, "ratePercent");
        Objects.requireNonNull(excessRatePercent, "excessRatePercent");
        if (limit.signum() < 0) {
            throw new IllegalArgumentException("Une autorisation de decouvert est positive ou nulle : "
                                               + limit);
        }
    }

    @Override
    public Money accrue(Money balance, BigDecimal yearFraction) {
        BigDecimal basis = balance.amount();
        BigDecimal within = basis.min(limit);
        BigDecimal excess = basis.subtract(within);
        Money inside = Money.of(within.setScale(Money.MAX_INTERNAL_SCALE, java.math.RoundingMode.HALF_EVEN),
                                balance.currency())
            .times(ratePercent.divide(HUNDRED, MathContexts.RATE)).times(yearFraction);
        Money beyond = Money.of(excess.setScale(Money.MAX_INTERNAL_SCALE, java.math.RoundingMode.HALF_EVEN),
                                balance.currency())
            .times(excessRatePercent.divide(HUNDRED, MathContexts.RATE)).times(yearFraction);
        return inside.plus(beyond);
    }

    /** Taux moyen constate sur l'assiette : restitution seulement, jamais un facteur de calcul. */
    @Override
    public BigDecimal effectiveRate(Money balance) {
        BigDecimal basis = balance.amount();
        if (basis.signum() == 0 || basis.compareTo(limit) <= 0) {
            return ratePercent;
        }
        BigDecimal weighted = limit.multiply(ratePercent)
            .add(basis.subtract(limit).multiply(excessRatePercent));
        return weighted.divide(basis, MathContexts.RATE).setScale(6, java.math.RoundingMode.HALF_EVEN);
    }
}
