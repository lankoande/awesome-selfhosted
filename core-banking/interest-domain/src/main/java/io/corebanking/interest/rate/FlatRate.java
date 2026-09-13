package io.corebanking.interest.rate;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/** Taux unique, exprime en pourcentage annuel (3.5 signifie 3,5 % l'an). */
public record FlatRate(BigDecimal annualRatePercent) implements RateSchedule {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public FlatRate {
        Objects.requireNonNull(annualRatePercent, "annualRatePercent");
    }

    public static FlatRate of(String percent) {
        return new FlatRate(new BigDecimal(percent));
    }

    @Override
    public Money accrue(Money balance, BigDecimal yearFraction) {
        return balance.times(annualRatePercent.divide(HUNDRED, MathContexts.RATE))
                      .times(yearFraction);
    }

    @Override
    public BigDecimal effectiveRate(Money balance) {
        return annualRatePercent;
    }
}
