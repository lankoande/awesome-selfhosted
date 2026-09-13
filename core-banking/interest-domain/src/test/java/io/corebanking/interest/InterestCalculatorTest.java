package io.corebanking.interest;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.interest.accrual.InterestBasis;
import io.corebanking.interest.accrual.InterestCalculator;
import io.corebanking.interest.accrual.PeriodAccrual;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterestCalculatorTest {

    private static final LocalDate DEBUT = LocalDate.of(2026, 1, 1);

    private static List<DailyBalance> constant(String amount, int days) {
        List<DailyBalance> series = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            series.add(new DailyBalance(DEBUT.plusDays(i), Money.of(amount, XOF)));
        }
        return series;
    }

    @Test
    @DisplayName("un solde constant sur un an rend exactement capital x taux")
    void constant_balance_over_a_year_is_exact() {
        PeriodAccrual accrual = InterestCalculator.accrue(
            constant("1200000", 365), InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR,
            FlatRate.of("3.5"), DayCountConvention.ACT_365);

        // 1 200 000 x 3,5 % = 42 000 exactement, sans derive d'arrondi sur 365 journees.
        assertThat(accrual.bookableAmount()).isEqualTo(Money.of("42000", XOF));
        assertThat(accrual.days()).hasSize(365);
    }

    @Test
    @DisplayName("le solde minimum et le solde quotidien donnent des montants tres differents")
    void minimum_balance_is_far_less_generous() {
        // Le client depose 10 000 000 le 2 du mois et les conserve jusqu'a la fin.
        List<DailyBalance> serie = new ArrayList<>();
        serie.add(new DailyBalance(DEBUT, Money.of("100000", XOF)));
        for (int i = 1; i < 31; i++) {
            serie.add(new DailyBalance(DEBUT.plusDays(i), Money.of("10000000", XOF)));
        }

        Money quotidien = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("6"), DayCountConvention.ACT_365).total();
        Money minimum = InterestCalculator.accrue(serie, InterestBasis.MINIMUM_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("6"), DayCountConvention.ACT_365).total();

        // Une seule journee a 100 000 suffit a ramener toute l'assiette a ce niveau.
        assertThat(minimum).isLessThan(quotidien);
        assertThat(quotidien.dividedBy(minimum.amount()).amount().intValue()).isGreaterThan(90);
    }

    @Test
    @DisplayName("interets crediteurs et agios se calculent separement et ne se compensent jamais")
    void creditor_and_debtor_scales_are_separate() {
        // 10 jours a +1 000 000, puis 10 jours a -1 000 000.
        List<DailyBalance> serie = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            serie.add(new DailyBalance(DEBUT.plusDays(i), Money.of("1000000", XOF)));
        }
        for (int i = 10; i < 20; i++) {
            serie.add(new DailyBalance(DEBUT.plusDays(i), Money.of("-1000000", XOF)));
        }

        Money crediteurs = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("3"), DayCountConvention.ACT_365).total();
        Money agios = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.DEBTOR, FlatRate.of("15"), DayCountConvention.ACT_365).total();

        // Chaque cote ne retient que ses journees : ni compensation, ni solde net.
        assertThat(crediteurs.isPositive()).isTrue();
        assertThat(agios.isPositive()).isTrue();
        // A taux cinq fois superieur sur le meme nombre de jours, les agios valent 5 fois plus.
        assertThat(agios.amount().divide(crediteurs.amount(), java.math.MathContext.DECIMAL32)
                        .setScale(2, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("une serie discontinue est refusee : une journee manquante est un interet perdu")
    void discontinuous_series_is_rejected() {
        List<DailyBalance> serie = List.of(
            new DailyBalance(DEBUT, Money.of("1000000", XOF)),
            new DailyBalance(DEBUT.plusDays(2), Money.of("1000000", XOF)));   // le 2 manque

        assertThatThrownBy(() -> InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("3"), DayCountConvention.ACT_365))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("discontinue");
    }

    @Test
    @DisplayName("chaque journee reste explicable : assiette, taux et fraction sont conserves")
    void every_accrual_stays_explainable() {
        PeriodAccrual accrual = InterestCalculator.accrue(
            constant("5000000", 3), InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR,
            FlatRate.of("7.25"), DayCountConvention.ACT_360);

        var premier = accrual.days().get(0);
        assertThat(premier.day()).isEqualTo(DEBUT);
        assertThat(premier.basisBalance()).isEqualTo(Money.of("5000000", XOF));
        assertThat(premier.effectiveRate()).isEqualByComparingTo("7.25");
        // 5 000 000 x 7,25 % / 360 = 1 006,944 44 par jour, conserve en precision interne.
        assertThat(premier.amount()).isEqualTo(Money.of("1006.94444", XOF));
    }

    @Test
    @DisplayName("l'ecart d'arrondi est restitue et jamais absorbe silencieusement")
    void rounding_remainder_is_carried_out() {
        PeriodAccrual accrual = InterestCalculator.accrue(
            constant("1234567", 30), InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR,
            FlatRate.of("4.75"), DayCountConvention.ACT_365);

        assertThat(accrual.bookableAmount().plus(accrual.roundingRemainder()))
            .isEqualTo(accrual.total());
        assertThat(accrual.roundingRemainder().abs().isLessThan(Money.of("1", XOF))).isTrue();
    }
}
