package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deblocage echelonne : le plan, les interets intercalaires, l'echeancier definitif.
 *
 * <p>Le cas de reference est un credit de construction de 10 000 000 XOF accorde le 15 janvier
 * 2026, mobilisable jusqu'au 15 juin, amortissable en vingt-quatre mensualites a compter du
 * 15 juillet. Deux tranches : 4 000 000 a la signature, 6 000 000 au constat des fondations, le
 * 20 mars.
 */
class DisbursementPlanTest {

    private static final LocalDate SIGNATURE = LocalDate.of(2026, 1, 15);
    private static final LocalDate FONDATIONS = LocalDate.of(2026, 3, 20);
    private static final LocalDate LIMITE = LocalDate.of(2026, 6, 15);
    private static final LocalDate PREMIERE_ECHEANCE = LocalDate.of(2026, 7, 15);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static LoanTerms conditions() {
        return LoanTerms.of(xof("10000000")).ratePercent("12").instalments(24)
            .disbursedOn(SIGNATURE).firstDueDate(PREMIERE_ECHEANCE).build();
    }

    private static DisbursementPlan plan() {
        return DisbursementPlan.of(Currencies.XOF)
            .tranche(SIGNATURE, xof("4000000"), "signature")
            .tranche(FONDATIONS, xof("6000000"), "fondations achevees")
            .deadline(LIMITE)
            .build();
    }

    // ------------------------------------------------------------------ le plan

    @Test
    @DisplayName("le total des tranches est l'engagement de la banque")
    void engagement() {
        assertThat(plan().committed()).isEqualTo(xof("10000000"));
        assertThat(plan().size()).isEqualTo(2);
        assertThat(plan().tranche(2).condition()).isEqualTo("fondations achevees");
        plan().requireConsistentWith(conditions());
    }

    @Test
    @DisplayName("un plan dont le total ne fait pas le capital accorde est refuse")
    void totalDifferent() {
        DisbursementPlan plan = DisbursementPlan.of(Currencies.XOF)
            .tranche(SIGNATURE, xof("4000000"))
            .tranche(FONDATIONS, xof("5000000"))
            .deadline(LIMITE).build();
        // L'ecart de 1 000 000 serait soit debloque hors plan, soit perdu pour l'emprunteur. Aucun
        // controle comptable ne le verrait : les ecritures des deux tranches sont equilibrees.
        assertThatThrownBy(() -> plan.requireConsistentWith(conditions()))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("9000000 XOF")
            .hasMessageContaining("10000000 XOF");
    }

    @Test
    @DisplayName("une mobilisation qui deborde sur l'amortissement est refusee")
    void mobilisationTropLongue() {
        DisbursementPlan plan = DisbursementPlan.of(Currencies.XOF)
            .tranche(SIGNATURE, xof("4000000"))
            .tranche(LocalDate.of(2026, 7, 20), xof("6000000"))
            .deadline(LocalDate.of(2026, 7, 31)).build();
        assertThatThrownBy(() -> plan.requireConsistentWith(conditions()))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("rouvrir des echeances deja reclamees");
    }

    @Test
    @DisplayName("une mobilisation close la veille de la premiere echeance est refusee")
    void mobilisationJusquALaVeille() {
        DisbursementPlan plan = DisbursementPlan.of(Currencies.XOF)
            .tranche(SIGNATURE, xof("4000000"))
            .tranche(LocalDate.of(2026, 7, 14), xof("6000000"))
            .deadline(LocalDate.of(2026, 7, 14)).build();
        // Il ne resterait aucun jour entre la cloture et la premiere echeance : la premiere periode
        // d'amortissement serait vide, et son interet nul sans que personne ne l'ait decide.
        assertThatThrownBy(() -> plan.requireConsistentWith(conditions()))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("aucun jour");
    }

    @Test
    @DisplayName("un plan mal forme est refuse a la construction")
    void planMalForme() {
        assertThatThrownBy(() -> new DisbursementPlan(Currencies.XOF, List.of(), LIMITE))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("aucune tranche");

        assertThatThrownBy(() -> new DisbursementPlan(Currencies.XOF, List.of(
            Tranche.of(1, SIGNATURE, xof("4000000")),
            Tranche.of(3, FONDATIONS, xof("6000000"))), LIMITE))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("sans trou");

        assertThatThrownBy(() -> new DisbursementPlan(Currencies.XOF, List.of(
            Tranche.of(1, FONDATIONS, xof("4000000")),
            Tranche.of(2, SIGNATURE, xof("6000000"))), LIMITE))
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("avant la precedente");

        assertThatThrownBy(() -> Tranche.of(1, SIGNATURE, xof("0")))
            .isInstanceOf(InvalidDisbursementPlanException.class);

        // Une date limite anterieure a la derniere tranche rendrait celle-ci indebloquable.
        assertThatThrownBy(() -> DisbursementPlan.of(Currencies.XOF)
            .tranche(SIGNATURE, xof("4000000"))
            .tranche(FONDATIONS, xof("6000000"))
            .deadline(LocalDate.of(2026, 2, 1)).build())
            .isInstanceOf(InvalidDisbursementPlanException.class)
            .hasMessageContaining("ne pourrait jamais etre debloquee");
    }

    // ------------------------------------------------------------------ interets intercalaires

    private static List<InterimInterest.Drawing> deblocages() {
        return List.of(new InterimInterest.Drawing(SIGNATURE, xof("4000000")),
                       new InterimInterest.Drawing(FONDATIONS, xof("6000000")));
    }

    private static Money intercalaires(LocalDate du, LocalDate au) {
        return InterimInterest.accrue(deblocages(), du, au, new BigDecimal("12"),
                                      DayCountConvention.ACT_365, Currencies.XOF).interest();
    }

    @Test
    @DisplayName("les interets intercalaires ne courent que sur le montant mobilise")
    void assietteMobilisee() {
        // Du 15 janvier au 15 février inclus, soit trente-deux jours sur les 4 000 000 verses a la
        // signature : 4 000 000 x 12 % x 32/365 = 42 082.
        assertThat(intercalaires(SIGNATURE, LocalDate.of(2026, 2, 15))).isEqualTo(xof("42082"));

        // Sur le montant accorde, la meme periode couterait 105 205 : deux fois et demie plus, pour
        // des fonds que l'emprunteur n'a pas recus. C'est le prix exact du contournement qui
        // consiste a tout debloquer sur un compte d'attente.
        Money surLEngagement = InterimInterest.accrue(
            List.of(new InterimInterest.Drawing(SIGNATURE, xof("10000000"))), SIGNATURE,
            LocalDate.of(2026, 2, 15), new BigDecimal("12"), DayCountConvention.ACT_365,
            Currencies.XOF).interest();
        assertThat(surLEngagement).isEqualTo(xof("105205"));
    }

    @Test
    @DisplayName("une tranche debloquee en cours de periode ne porte interet qu'a compter de sa date")
    void deblocageEnCoursDePeriode() {
        // Periode du 16 mars au 15 avril : quatre jours a 4 000 000 puis vingt-sept a 10 000 000.
        // 4 000 000 x 12 % x 4/365 = 5 260,27 et 10 000 000 x 12 % x 27/365 = 88 767,12, soit
        // 94 027,39 arrondis a 94 027. L'arrondi n'intervient qu'une fois, sur le total.
        assertThat(intercalaires(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 15)))
            .isEqualTo(xof("94027"));

        // La tranche porte interet des le jour de sa mise a disposition, pas le lendemain.
        assertThat(InterimInterest.drawnAt(deblocages(), FONDATIONS, Currencies.XOF))
            .isEqualTo(xof("10000000"));
        assertThat(InterimInterest.drawnAt(deblocages(), FONDATIONS.minusDays(1), Currencies.XOF))
            .isEqualTo(xof("4000000"));
    }

    @Test
    @DisplayName("les periodes intercalaires se calent a rebours sur la premiere echeance")
    void periodesCaleesSurLEcheance() {
        List<LocalDate> fins = InterimInterest.periodEnds(conditions(), LIMITE);
        // Le client paie ses interets intercalaires le 15, comme il paiera ses echeances. La
        // premiere fin de periode n'est pas a trente jours du deblocage mais au premier 15 qui
        // suit.
        assertThat(fins).containsExactly(
            LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15),
            LocalDate.of(2026, 5, 15), LocalDate.of(2026, 6, 15));

        // Aucune fin de periode ne tombe le jour du deblocage ni le jour de la premiere echeance :
        // la premiere n'aurait aucun jour, la seconde ferait double emploi avec l'echeance.
        assertThat(InterimInterest.periodEnds(conditions(), PREMIERE_ECHEANCE.plusMonths(3)))
            .isEqualTo(fins);
        assertThat(fins).doesNotContain(SIGNATURE, PREMIERE_ECHEANCE);
    }

    @Test
    @DisplayName("la serie des periodes couvre la mobilisation sans trou ni recouvrement")
    void serieContinue() {
        List<LocalDate> fins = InterimInterest.periodEnds(conditions(), LIMITE);
        LocalDate debut = SIGNATURE;
        Money total = Money.zero(Currencies.XOF);
        for (LocalDate fin : fins) {
            total = total.plus(intercalaires(debut, fin));
            debut = fin.plusDays(1);
        }
        // 42 082 + 36 822 + 94 027 + 98 630 + 101 918.
        assertThat(total).isEqualTo(xof("373479"));
        // La derniere periode s'arrete la veille de la prise d'effet de l'echeancier definitif :
        // aucun jour n'est facture deux fois, aucun n'est oublie.
        assertThat(debut).isEqualTo(LIMITE.plusDays(1));
    }

    // ------------------------------------------------------------------ echeancier definitif

    @Test
    @DisplayName("tirer moins que le montant accorde reduit l'echeance, pas la duree")
    void echeancierSurLeMobilise() {
        LoanTerms complet = conditions().forDrawn(xof("10000000"), LIMITE.plusDays(1));
        LoanTerms partiel = conditions().forDrawn(xof("9000000"), LIMITE.plusDays(1));

        AmortisationSchedule surTout = ScheduleGenerator.generate(complet);
        AmortisationSchedule surPartie = ScheduleGenerator.generate(partiel);

        assertThat(surTout.instalments()).hasSize(24);
        assertThat(surPartie.instalments()).hasSize(24);
        assertThat(surTout.instalment(24).dueDate()).isEqualTo(surPartie.instalment(24).dueDate());
        // 470 735 sur les 10 000 000 accordes, 423 661 sur les 9 000 000 reellement tires :
        // l'echeance suit le mobilise, le terme reste celui du contrat.
        assertThat(surTout.instalment(1).total()).isEqualTo(xof("470735"));
        assertThat(surPartie.instalment(1).total()).isEqualTo(xof("423661"));

        // La premiere periode d'interets s'ouvre a la cloture de la mobilisation, jamais a la
        // signature : les mois precedents ont deja ete factures en intercalaires.
        assertThat(surTout.instalment(1).periodStart()).isEqualTo(LocalDate.of(2026, 6, 16));
    }

    @Test
    @DisplayName("un echeancier sur plus que le montant accorde est refuse")
    void mobiliseSuperieurAAccorde() {
        assertThatThrownBy(() -> conditions().forDrawn(xof("11000000"), LIMITE.plusDays(1)))
            .isInstanceOf(LoanTerms.InvalidLoanTermsException.class)
            .hasMessageContaining("superieur au montant accorde");
    }

    // ------------------------------------------------------------------ taux effectif

    @Test
    @DisplayName("ignorer les dates de deblocage sous-estime le taux effectif")
    void tauxEffectifDesTranches() {
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            conditions().forDrawn(xof("10000000"), LIMITE.plusDays(1)));

        List<DatedFlow> recus = List.of(new DatedFlow(SIGNATURE, xof("4000000")),
                                        new DatedFlow(FONDATIONS, xof("6000000")));
        List<DatedFlow> payes = new java.util.ArrayList<>(List.of(
            new DatedFlow(SIGNATURE, xof("100000")),
            new DatedFlow(LocalDate.of(2026, 2, 15), xof("42082")),
            new DatedFlow(LocalDate.of(2026, 3, 15), xof("36822")),
            new DatedFlow(LocalDate.of(2026, 4, 15), xof("94027")),
            new DatedFlow(LocalDate.of(2026, 5, 15), xof("98630")),
            new DatedFlow(LocalDate.of(2026, 6, 15), xof("101918"))));
        echeancier.instalments()
            .forEach(e -> payes.add(new DatedFlow(e.dueDate(), e.total())));

        Teg reel = EffectiveRate.between(SIGNATURE, Periodicity.MONTHLY, recus, payes,
                                         RateAnnualisation.PROPORTIONAL);
        Teg commeSiToutVerseALOrigine = EffectiveRate.between(
            SIGNATURE, Periodicity.MONTHLY, List.of(new DatedFlow(SIGNATURE, xof("10000000"))),
            payes, RateAnnualisation.PROPORTIONAL);

        // 12,83 % contre 11,83 % : crediter l'emprunteur de fonds qu'il n'a pas encore recus fait
        // perdre un point entier de taux effectif. L'erreur va dans le sens qui fait passer sous
        // le plafond d'usure un credit qui le depasse.
        assertThat(reel.annualRatePercent()).isEqualByComparingTo("12.825563");
        assertThat(commeSiToutVerseALOrigine.annualRatePercent()).isEqualByComparingTo("11.833381");
        assertThat(reel.annualRatePercent())
            .isGreaterThan(commeSiToutVerseALOrigine.annualRatePercent());
        assertThat(reel.amountReceived()).isEqualTo(xof("10000000"));
    }
}
