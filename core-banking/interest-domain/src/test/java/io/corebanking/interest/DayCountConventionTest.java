package io.corebanking.interest;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.daycount.DayCountConvention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DayCountConventionTest {

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(10, RoundingMode.HALF_EVEN);
    }

    @Test
    @DisplayName("ACT/360 et ACT/365 different de 1,39 % sur une annee : l'ecart est conventionnel")
    void act360_and_act365_differ_by_convention() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2027, 1, 1);

        BigDecimal act360 = DayCountConvention.ACT_360.yearFraction(start, end);
        BigDecimal act365 = DayCountConvention.ACT_365.yearFraction(start, end);

        assertThat(round(act365)).isEqualByComparingTo("1.0000000000");
        assertThat(round(act360)).isEqualByComparingTo("1.0138888889");   // 365 / 360
    }

    @Test
    @DisplayName("ACT/ACT rend exactement 1 sur une annee, bissextile ou non")
    void act_act_returns_one_on_any_full_year() {
        assertThat(round(DayCountConvention.ACT_ACT_ISDA.yearFraction(
            LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))))       // 366 jours
            .isEqualByComparingTo("1.0000000000");
        assertThat(round(DayCountConvention.ACT_ACT_ISDA.yearFraction(
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))))       // 365 jours
            .isEqualByComparingTo("1.0000000000");
    }

    @Test
    @DisplayName("ACT/ACT decoupe une periode a cheval sur deux annees")
    void act_act_splits_across_years() {
        // 31 jours en 2023 (365 j) et 31 jours en 2024 (366 j).
        BigDecimal fraction = DayCountConvention.ACT_ACT_ISDA.yearFraction(
            LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 1));

        BigDecimal attendu = new BigDecimal("31").divide(new BigDecimal("365"), java.math.MathContext.DECIMAL64)
            .add(new BigDecimal("31").divide(new BigDecimal("366"), java.math.MathContext.DECIMAL64));

        assertThat(round(fraction)).isEqualByComparingTo(round(attendu));
    }

    @Test
    @DisplayName("en 30/360, tout mois vaut 30/360 — y compris un mois de 31 jours")
    void thirty_360_gives_every_month_the_same_weight() {
        // Janvier 2026 : 31 jours calendaires, mais 30 journees conventionnelles.
        BigDecimal janvier = sumOfDailyFractions(
            DayCountConvention.THIRTY_360_US, LocalDate.of(2026, 1, 1), 31);
        assertThat(round(janvier)).isEqualByComparingTo(round(
            new BigDecimal("30").divide(new BigDecimal("360"), java.math.MathContext.DECIMAL64)));
    }

    @Test
    @DisplayName("en 30/360, fevrier vaut aussi 30/360 malgre ses 28 jours")
    void thirty_360_compensates_february() {
        BigDecimal fevrier = sumOfDailyFractions(
            DayCountConvention.THIRTY_360_US, LocalDate.of(2026, 2, 1), 28);
        assertThat(round(fevrier)).isEqualByComparingTo(round(
            new BigDecimal("30").divide(new BigDecimal("360"), java.math.MathContext.DECIMAL64)));
    }

    @Test
    @DisplayName("30/360 US et 30E/360 divergent sur les fins de mois a 31 jours")
    void us_and_european_thirty_360_differ() {
        LocalDate start = LocalDate.of(2026, 1, 30);
        LocalDate end = LocalDate.of(2026, 3, 31);

        BigDecimal us = DayCountConvention.THIRTY_360_US.yearFraction(start, end);
        BigDecimal eu = DayCountConvention.THIRTY_E_360.yearFraction(start, end);

        // US ramene le 31 a 30 seulement si la borne de depart est deja au 30 : ici 60 jours.
        assertThat(round(us)).isEqualByComparingTo(round(
            new BigDecimal("60").divide(new BigDecimal("360"), java.math.MathContext.DECIMAL64)));
        // L'europeenne ramene systematiquement : 60 jours egalement, mais par une autre regle.
        assertThat(round(eu)).isEqualByComparingTo(round(us));

        // La divergence apparait quand la borne de depart n'est pas au 30.
        LocalDate depart = LocalDate.of(2026, 1, 15);
        assertThat(DayCountConvention.THIRTY_360_US.yearFraction(depart, end))
            .isNotEqualByComparingTo(DayCountConvention.THIRTY_E_360.yearFraction(depart, end));
    }

    @Test
    @DisplayName("les fractions journalieres reconstituent exactement la fraction de periode en ACT/*")
    void daily_fractions_sum_to_period_fraction() {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 6, 1);
        for (DayCountConvention convention :
             new DayCountConvention[] {DayCountConvention.ACT_360, DayCountConvention.ACT_365,
                                       DayCountConvention.ACT_ACT_ISDA}) {
            BigDecimal somme = BigDecimal.ZERO;
            for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
                somme = somme.add(convention.dayFraction(day));
            }
            assertThat(round(somme))
                .as("convention %s", convention)
                .isEqualByComparingTo(round(convention.yearFraction(start, end)));
        }
    }

    private static BigDecimal sumOfDailyFractions(DayCountConvention convention, LocalDate start,
                                                  int days) {
        BigDecimal somme = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            somme = somme.add(convention.dayFraction(start.plusDays(i)));
        }
        return somme;
    }
}
