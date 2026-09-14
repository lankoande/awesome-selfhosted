package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static final LocalDate DEBLOCAGE = LocalDate.of(2026, 9, 15);
    private static final LocalDate PREMIERE = LocalDate.of(2026, 10, 15);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static LoanTerms.Builder credit(String capital) {
        return LoanTerms.of(xof(capital)).disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE);
    }

    // ------------------------------------------------------------------ annuites constantes

    @Test
    @DisplayName("annuites constantes : l'echeance ne varie pas, sauf la derniere qui absorbe l'ecart")
    void annuitesConstantes() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("1000000").ratePercent("12").instalments(12).build());

        // 1 000 000 a 1 % par mois sur 12 mois : annuite de 88 849 XOF.
        assertThat(echeancier.instalments().subList(0, 11))
            .allSatisfy(e -> assertThat(e.total()).isEqualTo(xof("88849")));

        // La derniere solde le capital restant du. Son total differe de deux francs : c'est
        // exactement l'ecart d'arrondi cumule sur onze echeances, rendu visible au lieu d'etre
        // laisse en solde residuel apres la fin du credit.
        Instalment derniere = echeancier.last();
        assertThat(derniere.total()).isEqualTo(xof("88847"));
        assertThat(derniere.principal()).isEqualTo(xof("87967"));
        assertThat(derniere.outstandingAfter().isZero()).isTrue();

        assertThat(echeancier.instalment(1).interest()).isEqualTo(xof("10000"));
        assertThat(echeancier.instalment(1).principal()).isEqualTo(xof("78849"));
        assertThat(echeancier.totalInterest()).isEqualTo(xof("66186"));
    }

    @Test
    @DisplayName("la somme des capitaux amortis egale exactement le capital emprunte")
    void sommeDesCapitaux() {
        // La contrainte est portee par AmortisationSchedule : un ecart rendrait l'echeancier
        // impossible a construire. Le test balaie assez de combinaisons pour que l'absorption par
        // la derniere echeance soit reellement sollicitee.
        for (int echeances : new int[] {3, 7, 12, 13, 37, 60, 120}) {
            for (String taux : new String[] {"0", "0.5", "5.75", "12", "24"}) {
                for (AmortisationMethod methode : AmortisationMethod.values()) {
                    AmortisationSchedule echeancier = ScheduleGenerator.generate(
                        credit("1234567").ratePercent(taux).instalments(echeances)
                            .method(methode).build());

                    Money capitaux = Money.zero(Currencies.XOF);
                    for (Instalment e : echeancier.instalments()) {
                        capitaux = capitaux.plus(e.principal());
                    }
                    assertThat(capitaux).as("%s, %s echeances a %s %%", methode, echeances, taux)
                        .isEqualTo(xof("1234567"));
                    assertThat(echeancier.last().outstandingAfter().isZero()).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("une annuite qui ne couvre pas les interets est refusee, pas produite")
    void annuiteQuiNAmortitPas() {
        // 100 XOF sur trente ans a 12 % : l'annuite exacte vaut 1,0286 et s'impute a 1, soit
        // exactement les interets du premier mois. Le capital ne diminuerait jamais.
        //
        // Le cas n'est pas theorique : c'est celui des tres petits montants sur longue duree en
        // devise sans subdivision. Un generateur naif produirait un echeancier d'apparence normale
        // dont la derniere echeance reclamerait la totalite du capital.
        assertThatThrownBy(() -> ScheduleGenerator.generate(
            credit("100").ratePercent("12").instalments(360).build()))
            .isInstanceOf(LoanTerms.InvalidLoanTermsException.class)
            .hasMessageContaining("ne couvre pas les interets");
    }

    // ------------------------------------------------------------------ capital constant

    @Test
    @DisplayName("capital constant : la part de capital ne varie pas, l'interet suit les jours reels")
    void capitalConstant() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("1000000").ratePercent("12").instalments(12)
                .method(AmortisationMethod.CONSTANT_PRINCIPAL)
                .dayCount(DayCountConvention.ACT_365).build());

        assertThat(echeancier.instalments().subList(0, 11))
            .allSatisfy(e -> assertThat(e.principal()).isEqualTo(xof("83333")));
        assertThat(echeancier.last().principal()).isEqualTo(xof("83337"));

        // Premiere periode : du 15 septembre au 15 octobre inclus, soit 31 jours.
        // 1 000 000 x 12 % x 31 / 365 = 10 191,78 -> 10 192.
        assertThat(echeancier.instalment(1).interest()).isEqualTo(xof("10192"));
        // Sixieme periode : 28 jours de fevrier, sur un capital restant du de 583 335.
        assertThat(echeancier.instalment(6).interest()).isEqualTo(xof("5370"));
        // L'echeance decroit : c'est la signature de la methode.
        assertThat(echeancier.instalment(1).total()).isGreaterThan(echeancier.last().total());
    }

    @Test
    @DisplayName("l'interet d'une echeance egale la somme des interets courus quotidiens de sa periode")
    void coherenceAvecLeMoteurDAccruals() {
        // C'est ce qui rend l'echeancier et le moteur d'interets courus racontables ensemble. Si
        // les deux divergeaient, le produit constate au fil de l'eau ne correspondrait pas a
        // l'interet reclame a l'echeance, et l'ecart n'aurait aucune explication comptable.
        for (DayCountConvention convention : new DayCountConvention[] {
                DayCountConvention.ACT_365, DayCountConvention.ACT_360,
                DayCountConvention.ACT_ACT_ISDA}) {
            AmortisationSchedule echeancier = ScheduleGenerator.generate(
                credit("7000000").ratePercent("8.25").instalments(18)
                    .method(AmortisationMethod.CONSTANT_PRINCIPAL)
                    .dayCount(convention).build());

            for (Instalment e : echeancier.instalments()) {
                BigDecimal fractionCumulee = BigDecimal.ZERO;
                for (LocalDate jour = e.periodStart(); !jour.isAfter(e.periodEnd());
                     jour = jour.plusDays(1)) {
                    fractionCumulee = fractionCumulee.add(convention.dayFraction(jour));
                }
                Money parAccruals = e.outstandingBefore()
                    .times(new BigDecimal("0.0825")).times(fractionCumulee).roundToCurrency();

                assertThat(e.interest()).as("%s, echeance %d", convention, e.number())
                    .isEqualTo(parAccruals);
            }
        }
    }

    // ------------------------------------------------------------------ in fine et differe

    @Test
    @DisplayName("in fine : le capital n'est rembourse qu'a la derniere echeance")
    void inFine() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("3000000").ratePercent("10").instalments(6)
                .method(AmortisationMethod.BULLET).build());

        assertThat(echeancier.instalments().subList(0, 5))
            .allSatisfy(e -> {
                assertThat(e.principal().isZero()).isTrue();
                assertThat(e.outstandingBefore()).isEqualTo(xof("3000000"));
            });
        assertThat(echeancier.last().principal()).isEqualTo(xof("3000000"));
    }

    @Test
    @DisplayName("differe d'amortissement : le capital reste intact, les interets sont servis")
    void differeDAmortissement() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("5000000").ratePercent("9").instalments(6).grace(2).build());

        assertThat(echeancier.instalment(1).principal().isZero()).isTrue();
        assertThat(echeancier.instalment(2).principal().isZero()).isTrue();
        assertThat(echeancier.instalment(2).outstandingAfter()).isEqualTo(xof("5000000"));
        // 5 000 000 a 0,75 % par mois : 37 500 d'interets pendant le differe.
        assertThat(echeancier.instalment(1).interest()).isEqualTo(xof("37500"));
        // L'annuite est ensuite calculee sur les quatre echeances restantes.
        assertThat(echeancier.instalment(3).principal()).isEqualTo(xof("1236025"));
        assertThat(echeancier.last().outstandingAfter().isZero()).isTrue();
    }

    @Test
    @DisplayName("un differe qui consomme toutes les echeances est refuse")
    void differeTropLong() {
        assertThatThrownBy(() -> credit("1000000").ratePercent("9").instalments(6).grace(6).build())
            .isInstanceOf(LoanTerms.InvalidLoanTermsException.class)
            .hasMessageContaining("aucune echeance pour amortir");
    }

    // ------------------------------------------------------------------ assurance, frais, taxe

    @Test
    @DisplayName("l'assiette de l'assurance change le cout du credit, et le test le chiffre")
    void assietteDeLAssurance() {
        LoanTerms.Builder base = credit("5000000").ratePercent("9").instalments(24);

        AmortisationSchedule surCapitalInitial = ScheduleGenerator.generate(
            base.insurance(InsuranceBasis.INITIAL_PRINCIPAL, "0.05").build());
        AmortisationSchedule surCapitalRestant = ScheduleGenerator.generate(
            credit("5000000").ratePercent("9").instalments(24)
                .insurance(InsuranceBasis.OUTSTANDING_PRINCIPAL, "0.05").build());

        // Prime constante contre prime decroissante : sur ce credit, l'assiette du capital initial
        // coute presque le double. Les deux conventions sont licites et repandues ; c'est pourquoi
        // l'assiette est un parametre explicite du contrat, jamais un defaut.
        assertThat(surCapitalInitial.totalInsurance()).isEqualTo(xof("60000"));
        assertThat(surCapitalRestant.totalInsurance()).isEqualTo(xof("32145"));
    }

    @Test
    @DisplayName("la taxe porte sur l'interet de l'echeance et s'arrondit pour elle-meme")
    void taxeSurInterets() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("5000000").ratePercent("9").instalments(6).grace(2)
                .insurance(InsuranceBasis.INITIAL_PRINCIPAL, "0.05")
                .taxOnInterestPercent("18")
                .periodicFee(xof("1000")).build());

        Instalment quatrieme = echeancier.instalment(4);
        assertThat(quatrieme.interest()).isEqualTo(xof("28230"));
        // 28 230 x 18 % = 5 081,4 -> 5 081, arrondi pour lui-meme et non deduit du total.
        assertThat(quatrieme.tax()).isEqualTo(xof("5081"));
        assertThat(quatrieme.total()).isEqualTo(
            quatrieme.principal().plus(quatrieme.interest()).plus(quatrieme.insurance())
                     .plus(quatrieme.fee()).plus(quatrieme.tax()));
    }

    @Test
    @DisplayName("un taux d'assurance sans assiette est refuse : la prime ne serait jamais calculee")
    void assuranceSansAssiette() {
        assertThatThrownBy(() -> credit("1000000").ratePercent("9").instalments(6)
            .insuranceRatePercent("0.05").build())
            .isInstanceOf(LoanTerms.InvalidLoanTermsException.class)
            .hasMessageContaining("sans assiette");
    }

    // ------------------------------------------------------------------ calendrier

    @Test
    @DisplayName("les periodes d'interet sont jointives et la premiere s'ouvre au deblocage")
    void periodesJointives() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("1000000").ratePercent("6").instalments(4).build());

        assertThat(echeancier.instalment(1).periodStart()).isEqualTo(DEBLOCAGE);
        assertThat(echeancier.instalment(1).periodEnd()).isEqualTo(PREMIERE);
        assertThat(echeancier.instalment(2).periodStart()).isEqualTo(PREMIERE.plusDays(1));
        // Verifie par AmortisationSchedule pour tout l'echeancier ; l'assertion ici nomme la regle.
        assertThat(echeancier.last().periodEnd()).isEqualTo(echeancier.last().dueDate());
    }

    @Test
    @DisplayName("une echeance au 31 ne derive pas apres fevrier")
    void echeancesEnFinDeMois() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("6").instalments(5)
                .disbursedOn(LocalDate.of(2026, 1, 15))
                .firstDueDate(LocalDate.of(2026, 1, 31)).build());

        assertThat(echeancier.instalments()).extracting(Instalment::dueDate)
            .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                             LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30),
                             LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("une premiere echeance anterieure au deblocage est refusee")
    void premiereEcheanceAvantDeblocage() {
        assertThatThrownBy(() -> LoanTerms.of(xof("1000000")).ratePercent("6").instalments(6)
            .disbursedOn(LocalDate.of(2026, 9, 15))
            .firstDueDate(LocalDate.of(2026, 9, 1)).build())
            .isInstanceOf(LoanTerms.InvalidLoanTermsException.class)
            .hasMessageContaining("anterieure ou egale au deblocage");
    }

    @Test
    @DisplayName("un credit trimestriel a taux nul amortit en parts egales")
    void tauxNul() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            credit("1200000").ratePercent("0").instalments(4)
                .frequency(Periodicity.QUARTERLY).build());

        assertThat(echeancier.totalInterest().isZero()).isTrue();
        assertThat(echeancier.instalments()).allSatisfy(
            e -> assertThat(e.principal()).isEqualTo(xof("300000")));
        assertThat(echeancier.instalment(2).dueDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    }
}
