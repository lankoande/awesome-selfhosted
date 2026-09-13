package io.corebanking.interest.rate;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Bareme par tranches.
 *
 * <p>Les tranches doivent etre contigues et couvrir l'intervalle depuis zero : une lacune ou un
 * chevauchement rendrait le calcul dependant de l'ordre de parcours, donc non reproductible. La
 * verification est faite a la construction et non a l'execution, de facon qu'un bareme mal saisi
 * soit rejete au deploiement du parametrage, pas decouvert au TFJ.
 */
public record TieredRate(List<Tier> tiers, TieringMode mode) implements RateSchedule {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public TieredRate {
        tiers = List.copyOf(Objects.requireNonNull(tiers, "tiers"));
        Objects.requireNonNull(mode, "mode");
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("Bareme sans tranche");
        }
        if (tiers.get(0).from().signum() != 0) {
            throw new IllegalArgumentException(
                "La premiere tranche doit partir de zero, elle part de " + tiers.get(0).from());
        }
        for (int i = 0; i < tiers.size() - 1; i++) {
            BigDecimal upper = tiers.get(i).to();
            if (upper == null) {
                throw new IllegalArgumentException(
                    "Seule la derniere tranche peut etre ouverte ; tranche " + i + " ne l'est pas.");
            }
            if (upper.compareTo(tiers.get(i + 1).from()) != 0) {
                throw new IllegalArgumentException(
                    "Tranches non contigues entre " + upper + " et " + tiers.get(i + 1).from());
            }
        }
        if (tiers.get(tiers.size() - 1).to() != null) {
            throw new IllegalArgumentException("La derniere tranche doit etre ouverte vers le haut.");
        }
    }

    @Override
    public Money accrue(Money balance, BigDecimal yearFraction) {
        BigDecimal amount = balance.amount();
        if (amount.signum() <= 0) {
            return Money.of(BigDecimal.ZERO, balance.currency());
        }
        if (mode == TieringMode.WHOLE_BALANCE) {
            return balance.times(rateOf(amount).divide(HUNDRED, MathContexts.RATE))
                          .times(yearFraction);
        }
        Money total = Money.of(BigDecimal.ZERO, balance.currency());
        for (Tier tier : tiers) {
            BigDecimal upper = tier.to() == null ? amount : tier.to().min(amount);
            BigDecimal slice = upper.subtract(tier.from());
            if (slice.signum() <= 0) {
                continue;
            }
            total = total.plus(Money.of(slice, balance.currency())
                .times(tier.annualRatePercent().divide(HUNDRED, MathContexts.RATE))
                .times(yearFraction));
        }
        return total;
    }

    @Override
    public BigDecimal effectiveRate(Money balance) {
        return rateOf(balance.amount());
    }

    private BigDecimal rateOf(BigDecimal amount) {
        for (Tier tier : tiers) {
            if (tier.contains(amount)) {
                return tier.annualRatePercent();
            }
        }
        return tiers.get(tiers.size() - 1).annualRatePercent();
    }
}
