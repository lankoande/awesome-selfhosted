package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrepaymentTest {

    private static final LocalDate DEBLOCAGE = LocalDate.of(2026, 9, 15);
    private static final LocalDate PREMIERE = LocalDate.of(2026, 10, 15);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    /** 1 000 000 XOF a 12 % sur vingt-quatre mensualites : annuite de 47 073 XOF. */
    private static LoanTerms conditions() {
        return LoanTerms.of(xof("1000000")).ratePercent("12").instalments(24)
            .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE).build();
    }

    // ------------------------------------------------------------------ les deux options

    @Test
    @DisplayName("reduire la duree fait economiser plus d'interets que reduire l'echeance")
    void lesDeuxOptionsChiffrees() {
        LoanTerms conditions = conditions();
        AmortisationSchedule initial = ScheduleGenerator.generate(conditions);
        // Apres six echeances, le capital restant du est de 771 927 ; l'emprunteur en rembourse
        // 300 000 par anticipation.
        Money restant = initial.instalment(7).outstandingBefore();
        Money apres = restant.minus(xof("300000"));
        Money annuite = initial.instalment(7).principal().plus(initial.instalment(7).interest());

        AmortisationSchedule dureeReduite = Prepayments.rebuild(
            conditions, apres, LocalDate.of(2027, 3, 15), LocalDate.of(2027, 4, 15), 18,
            PrepaymentMode.SHORTEN_TERM, annuite).orElseThrow();
        AmortisationSchedule echeanceReduite = Prepayments.rebuild(
            conditions, apres, LocalDate.of(2027, 3, 15), LocalDate.of(2027, 4, 15), 18,
            PrepaymentMode.REDUCE_INSTALMENT, annuite).orElseThrow();

        // Meme capital rembourse par anticipation, deux plans : l'un s'eteint en onze echeances,
        // l'autre en dix-huit. L'ecart d'interets est le prix du confort de tresorerie.
        assertThat(dureeReduite.instalments()).hasSize(11);
        assertThat(echeanceReduite.instalments()).hasSize(18);
        assertThat(dureeReduite.totalInterest()).isLessThan(echeanceReduite.totalInterest());
        assertThat(dureeReduite.totalInterest()).isEqualTo(xof("28785"));
        assertThat(echeanceReduite.totalInterest()).isEqualTo(xof("46096"));

        // L'echeance reste au niveau du contrat dans un cas — 45 519, soit au plus les 47 073
        // d'origine — et tombe a 28 779 dans l'autre. C'est ce qui rend les deux options
        // reellement differentes pour l'emprunteur, et non deux facons de dire la meme chose.
        assertThat(dureeReduite.instalment(1).total()).isEqualTo(xof("45519"));
        assertThat(dureeReduite.instalment(1).total()).isLessThan(xof("47073"));
        assertThat(echeanceReduite.instalment(1).total()).isEqualTo(xof("28779"));
    }

    @Test
    @DisplayName("un remboursement qui solde le capital ne produit aucun nouvel echeancier")
    void remboursementTotal() {
        LoanTerms conditions = conditions();
        AmortisationSchedule initial = ScheduleGenerator.generate(conditions);
        Money restant = initial.instalment(7).outstandingBefore();

        assertThat(Prepayments.rebuild(conditions, restant.minus(restant),
                                       LocalDate.of(2027, 3, 15), LocalDate.of(2027, 4, 15), 18,
                                       PrepaymentMode.SHORTEN_TERM, xof("47073")))
            .isEmpty();
    }

    @Test
    @DisplayName("un remboursement superieur au capital restant est refuse, pas transforme en avoir")
    void remboursementExcessif() {
        assertThatThrownBy(() -> Prepayments.rebuild(
            conditions(), xof("100").negate(), LocalDate.of(2027, 3, 15),
            LocalDate.of(2027, 4, 15), 18, PrepaymentMode.SHORTEN_TERM, xof("47073")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ne le rend pas crediteur");
    }

    @Test
    @DisplayName("le nouveau plan conserve le taux et les accessoires du contrat")
    void conditionsReconduites() {
        LoanTerms conditions = LoanTerms.of(xof("1000000")).ratePercent("12").instalments(24)
            .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE)
            .insurance(InsuranceBasis.OUTSTANDING_PRINCIPAL, "0.05")
            .periodicFee(xof("500")).taxOnInterestPercent("18").build();

        AmortisationSchedule nouveau = Prepayments.rebuild(
            conditions, xof("500000"), LocalDate.of(2027, 3, 15), LocalDate.of(2027, 4, 15), 18,
            PrepaymentMode.REDUCE_INSTALMENT, xof("47073")).orElseThrow();

        // Reviser le taux au passage transformerait un droit de l'emprunteur en renegociation.
        assertThat(nouveau.terms().annualRatePercent()).isEqualByComparingTo("12");
        assertThat(nouveau.instalment(1).fee()).isEqualTo(xof("500"));
        assertThat(nouveau.instalment(1).insurance()).isEqualTo(xof("250"));
        assertThat(nouveau.instalment(1).tax().isPositive()).isTrue();
        // Le differe ne se reconduit pas : il a ete consomme.
        assertThat(nouveau.terms().graceInstalments()).isZero();
    }

    @Test
    @DisplayName("in fine : il n'y a pas de duree a raccourcir, le capital est du a la fin")
    void inFine() {
        LoanTerms conditions = LoanTerms.of(xof("1000000")).ratePercent("10").instalments(8)
            .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE)
            .method(AmortisationMethod.BULLET).build();

        AmortisationSchedule nouveau = Prepayments.rebuild(
            conditions, xof("600000"), LocalDate.of(2027, 1, 15), LocalDate.of(2027, 2, 15), 5,
            PrepaymentMode.SHORTEN_TERM, xof("8333")).orElseThrow();

        assertThat(nouveau.instalments()).hasSize(5);
        assertThat(nouveau.last().principal()).isEqualTo(xof("600000"));
    }

    // ------------------------------------------------------------------ indemnite

    @Test
    @DisplayName("l'indemnite est plafonnee par les deux limites, et c'est la plus basse qui l'emporte")
    void doublePlafond() {
        Money capital = xof("3000000");

        // 3 % du capital rembourse feraient 90 000. Le plafond legal en pourcentage — 2 % —
        // ramene a 60 000 ; celui en mois d'interets — six mois a 12 % — a 180 000. C'est le plus
        // bas qui s'applique.
        assertThat(Prepayment.indemnity(capital, new BigDecimal("3"), new BigDecimal("2"),
                                        new BigDecimal("6"), new BigDecimal("12")))
            .isEqualTo(xof("60000"));

        // Sur un credit a taux faible, c'est le plafond en mois d'interets qui mord : six mois a
        // 1 % font 15 000.
        assertThat(Prepayment.indemnity(capital, new BigDecimal("3"), new BigDecimal("2"),
                                        new BigDecimal("6"), new BigDecimal("1")))
            .isEqualTo(xof("15000"));
    }

    @Test
    @DisplayName("sans plafond, l'indemnite contractuelle s'applique telle quelle")
    void sansPlafond() {
        assertThat(Prepayment.indemnity(xof("3000000"), new BigDecimal("3"), null, null, null))
            .isEqualTo(xof("90000"));
    }

    @Test
    @DisplayName("un credit sans indemnite n'en produit aucune")
    void sansIndemnite() {
        assertThat(Prepayment.indemnity(xof("3000000"), null, new BigDecimal("2"),
                                        new BigDecimal("6"), new BigDecimal("12")).isZero())
            .isTrue();
        assertThat(Prepayment.indemnity(xof("3000000"), BigDecimal.ZERO, null, null, null).isZero())
            .isTrue();
    }

    @Test
    @DisplayName("le montant reclame est le capital rembourse augmente de son indemnite")
    void montantReclame() {
        Prepayment remboursement = new Prepayment(
            LocalDate.of(2027, 3, 15), xof("300000"), xof("6000"), PrepaymentMode.SHORTEN_TERM,
            null);

        assertThat(remboursement.totalDue()).isEqualTo(xof("306000"));
        assertThat(remboursement.settlesLoan()).isTrue();
    }
}
