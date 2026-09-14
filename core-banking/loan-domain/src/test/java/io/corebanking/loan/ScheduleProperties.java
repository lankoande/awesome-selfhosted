package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Les invariants de l'echeancier, explores sur des combinaisons qu'aucune redaction manuelle
 * n'anticipe : capitaux premiers, durees longues, differes profonds, taux extremes.
 */
class ScheduleProperties {

    @Property(tries = 400)
    void un_echeancier_rembourse_exactement_le_capital_emprunte(
        @ForAll @IntRange(min = 1, max = 999_999_999) int capital,
        @ForAll @IntRange(min = 0, max = 3600) int centiemesDeTaux,
        @ForAll @IntRange(min = 2, max = 360) int echeances,
        @ForAll @IntRange(min = 0, max = 359) int differe,
        @ForAll AmortisationMethod methode,
        @ForAll Periodicity periodicite) {

        if (differe >= echeances) {
            return;                                   // refuse a la construction des conditions
        }
        LoanTerms conditions;
        try {
            conditions = LoanTerms.of(Money.of(capital, Currencies.XOF))
                .ratePercent(BigDecimal.valueOf(centiemesDeTaux, 2))
                .frequency(periodicite)
                .instalments(echeances)
                .grace(differe)
                .disbursedOn(LocalDate.of(2026, 1, 31))
                .firstDueDate(LocalDate.of(2026, 2, 28))
                .method(methode)
                .dayCount(DayCountConvention.ACT_365)
                .build();
        } catch (LoanTerms.InvalidLoanTermsException refus) {
            return;
        }

        AmortisationSchedule echeancier;
        try {
            echeancier = ScheduleGenerator.generate(conditions);
        } catch (LoanTerms.InvalidLoanTermsException refus) {
            // Le seul refus admis a la generation : une annuite qui n'amortit pas. Il ne peut
            // concerner que la methode a annuites constantes — les deux autres imposent la part de
            // capital et amortissent donc toujours.
            assertThat(methode).isEqualTo(AmortisationMethod.CONSTANT_ANNUITY);
            assertThat(refus).hasMessageContaining("ne couvre pas les interets");
            return;
        }

        Money amorti = Money.zero(Currencies.XOF);
        for (Instalment echeance : echeancier.instalments()) {
            amorti = amorti.plus(echeance.principal());
            assertThat(echeance.principal().isNegative()).isFalse();
            assertThat(echeance.interest().isBookable()).isTrue();
        }
        assertThat(amorti).isEqualTo(conditions.principal());
        assertThat(echeancier.last().outstandingAfter().isZero()).isTrue();
    }

    @Property(tries = 200)
    void le_differe_laisse_le_capital_intact(
        @ForAll @IntRange(min = 100_000, max = 100_000_000) int capital,
        @ForAll @IntRange(min = 1, max = 24) int differe,
        @ForAll @IntRange(min = 1, max = 60) int apresDiffere) {

        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            LoanTerms.of(Money.of(capital, Currencies.XOF))
                .ratePercent("9")
                .instalments(differe + apresDiffere)
                .grace(differe)
                .disbursedOn(LocalDate.of(2026, 9, 15))
                .firstDueDate(LocalDate.of(2026, 10, 15))
                .build());

        for (int rang = 1; rang <= differe; rang++) {
            Instalment echeance = echeancier.instalment(rang);
            assertThat(echeance.principal().isZero()).isTrue();
            assertThat(echeance.outstandingAfter()).isEqualTo(echeancier.terms().principal());
            // Le differe sert les interets : il n'est pas gratuit, et l'echeance n'est jamais nulle
            // des lors que le taux ne l'est pas.
            assertThat(echeance.total()).isEqualTo(echeance.interest());
        }
    }
}
