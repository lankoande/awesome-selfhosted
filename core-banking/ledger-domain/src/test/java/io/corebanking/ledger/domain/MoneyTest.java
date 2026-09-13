package io.corebanking.ledger.domain;

import static io.corebanking.kernel.money.Currencies.EUR;
import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.money.ScaleExceededException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("l'egalite est numerique : 100 et 100,00 sont le meme montant")
    void equality_is_numeric() {
        assertThat(Money.of("100", EUR)).isEqualTo(Money.of("100.00", EUR));
        assertThat(Money.of("100", EUR).hashCode()).isEqualTo(Money.of("100.00", EUR).hashCode());
    }

    @Test
    @DisplayName("un montant XOF a decimales n'est pas comptabilisable")
    void xof_has_no_subdivision() {
        assertThat(Money.of("1000", XOF).isBookable()).isTrue();
        assertThat(Money.of("1000.50", XOF).isBookable()).isFalse();
        assertThat(Money.of("1000.50", XOF).roundToCurrency()).isEqualTo(Money.of("1000", XOF));
    }

    @Test
    @DisplayName("la precision interne est bornee a 5 decimales")
    void internal_scale_is_bounded() {
        assertThatThrownBy(() -> Money.of("1.123456", XOF))
            .isInstanceOf(ScaleExceededException.class);
    }

    @Test
    @DisplayName("l'ecart d'arrondi n'est jamais perdu : il est restitue explicitement")
    void rounding_remainder_is_explicit() {
        Money accrued = Money.of("115.06849", XOF);
        assertThat(accrued.roundToCurrency()).isEqualTo(Money.of("115", XOF));
        assertThat(accrued.roundingRemainder()).isEqualTo(Money.of("0.06849", XOF));
        assertThat(accrued.roundToCurrency().plus(accrued.roundingRemainder())).isEqualTo(accrued);
    }

    @Test
    @DisplayName("arrondir chaque accrual quotidien coute 25 XOF par an et par compte")
    void daily_rounding_drifts_measurably() {
        // 1 200 000 XOF a 3,5 % l'an, base ACT/365, sur une annee pleine.
        Money principal = Money.of("1200000", XOF);
        BigDecimal dailyRate = new BigDecimal("0.035").divide(new BigDecimal("365"),
                                                              java.math.MathContext.DECIMAL64);
        Money dailyAccrual = principal.times(dailyRate);

        Money roundedEachDay = Money.zero(XOF);
        Money accumulated = Money.zero(XOF);
        for (int day = 0; day < 365; day++) {
            roundedEachDay = roundedEachDay.plus(dailyAccrual.roundToCurrency());
            accumulated = accumulated.plus(dailyAccrual);
        }
        Money accumulatedThenRounded = accumulated.roundToCurrency();

        assertThat(roundedEachDay).isEqualTo(Money.of("41975", XOF));
        assertThat(accumulatedThenRounded).isEqualTo(Money.of("42000", XOF));
        assertThat(accumulatedThenRounded.minus(roundedEachDay)).isEqualTo(Money.of("25", XOF));
    }

    @Test
    @DisplayName("XOF et XAF sont deux devises distinctes malgre la meme parite")
    void xof_and_xaf_are_distinct() {
        assertThatThrownBy(() -> Money.of("1000", XOF).plus(Money.of("1000",
            io.corebanking.kernel.money.Currencies.XAF)))
            .isInstanceOf(io.corebanking.kernel.money.CurrencyMismatchException.class);
    }
}
