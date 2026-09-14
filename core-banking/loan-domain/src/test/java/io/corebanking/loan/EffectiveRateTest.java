package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EffectiveRateTest {

    private static final LocalDate DEBLOCAGE = LocalDate.of(2026, 9, 15);
    private static final LocalDate PREMIERE = LocalDate.of(2026, 10, 15);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static LoanTerms.Builder credit() {
        return LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
            .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE);
    }

    private static AmortisationSchedule echeancier(LoanTerms.Builder builder) {
        return ScheduleGenerator.generate(builder.build());
    }

    // ------------------------------------------------------------------ le cout reel

    @Test
    @DisplayName("des frais de dossier de 2 % font passer le taux effectif de 12 % a pres de 16 %")
    void fraisDeDossier() {
        AmortisationSchedule echeancier = echeancier(credit());

        Teg sansFrais = EffectiveRate.of(echeancier, null, RateAnnualisation.PROPORTIONAL);
        Teg avecFrais = EffectiveRate.of(echeancier, xof("20000"),
                                         RateAnnualisation.PROPORTIONAL);

        // Le taux nominal est le meme, l'echeancier est le meme, et le cout du credit n'est pas le
        // meme : les frais preleves au deblocage ne sont pas rembourses, ils sont retenus. C'est
        // exactement ce que le taux effectif est fait pour rendre visible, et ce qu'un affichage
        // du seul taux nominal dissimule.
        assertThat(sansFrais.annualRatePercent()).isEqualByComparingTo("12.028136");
        assertThat(avecFrais.annualRatePercent()).isEqualByComparingTo("15.891722");

        assertThat(avecFrais.amountReceived()).isEqualTo(xof("980000"));
        assertThat(avecFrais.totalRepaid()).isEqualTo(sansFrais.totalRepaid());
        assertThat(avecFrais.totalCost()).isEqualTo(sansFrais.totalCost().plus(xof("20000")));
    }

    @Test
    @DisplayName("les deux methodes d'annualisation ne donnent pas le meme chiffre, et les deux sont licites")
    void proportionnelContreActuariel() {
        AmortisationSchedule echeancier = echeancier(credit());

        Teg proportionnel = EffectiveRate.of(echeancier, null, RateAnnualisation.PROPORTIONAL);
        Teg actuariel = EffectiveRate.of(echeancier, null, RateAnnualisation.ACTUARIAL);

        // Memes flux, deux chiffres : 12,03 % et 12,71 %. Presenter l'un pour l'autre n'est pas une
        // approximation, c'est une erreur de declaration — et sur un plafond d'usure a 12,5 %, le
        // meme credit est licite ou ne l'est pas selon la convention.
        assertThat(proportionnel.annualRatePercent()).isEqualByComparingTo("12.028136");
        assertThat(actuariel.annualRatePercent()).isEqualByComparingTo("12.713898");
        assertThat(actuariel.annualRatePercent())
            .isGreaterThan(proportionnel.annualRatePercent());
        // Le taux periodique, lui, est le meme : seule l'annualisation differe.
        assertThat(actuariel.periodicRatePercent())
            .isEqualByComparingTo(proportionnel.periodicRatePercent());
    }

    @Test
    @DisplayName("assurance et taxe entrent dans le taux effectif")
    void assuranceEtTaxe() {
        Teg nu = EffectiveRate.of(echeancier(credit()), null, RateAnnualisation.ACTUARIAL);
        Teg charge = EffectiveRate.of(
            echeancier(credit().insurance(InsuranceBasis.INITIAL_PRINCIPAL, "0.05")
                           .taxOnInterestPercent("18")),
            null, RateAnnualisation.ACTUARIAL);

        // Une assurance de 0,05 % par mois et une taxe de 18 % sur les interets ajoutent plus de
        // trois points et demi. Elles ne figurent dans aucun taux nominal.
        assertThat(nu.annualRatePercent()).isEqualByComparingTo("12.713898");
        assertThat(charge.annualRatePercent()).isEqualByComparingTo("16.373299");
    }

    @Test
    @DisplayName("un differe de premiere echeance abaisse le taux effectif, et la banque en supporte le cout")
    void differeDePremiereEcheance() {
        Teg standard = EffectiveRate.of(echeancier(credit()), null,
                                        RateAnnualisation.PROPORTIONAL);
        Teg differe = EffectiveRate.of(
            echeancier(credit().firstDueDate(LocalDate.of(2026, 10, 30))), null,
            RateAnnualisation.PROPORTIONAL);

        // Sur une annuite a taux periodique proportionnel, l'interet ne depend pas de la duree
        // reelle de la periode : quinze jours de plus se donnent gratuitement a l'emprunteur, et
        // le taux effectif chute de 12,03 % a 11,17 %. Le geste commercial est mesurable.
        assertThat(differe.annualRatePercent()).isEqualByComparingTo("11.169035");
        assertThat(differe.annualRatePercent()).isLessThan(standard.annualRatePercent());
    }

    @Test
    @DisplayName("un credit sans interet ni frais a un taux effectif nul")
    void creditGratuit() {
        Teg teg = EffectiveRate.of(
            echeancier(LoanTerms.of(xof("1200000")).ratePercent("0").instalments(12)
                           .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE)),
            null, RateAnnualisation.PROPORTIONAL);

        assertThat(teg.annualRatePercent().signum()).isZero();
        assertThat(teg.totalCost().isZero()).isTrue();
    }

    @Test
    @DisplayName("un credit bonifie, rembourse pour moins qu'il n'a ete recu, a un taux negatif")
    void creditBonifie() {
        // Le cas existe : credit agricole subventionne, pret d'honneur. Le refuser au lieu de le
        // chiffrer obligerait a le traiter hors systeme.
        //
        // Une bonification se represente par un capital recu superieur au capital rembourse : ici
        // 1 200 000 recus et un echeancier bati sur 1 100 000.
        AmortisationSchedule rembourse = echeancier(
            LoanTerms.of(xof("1100000")).ratePercent("0").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE));
        BigDecimal periodique = EffectiveRate.periodicRate(
            xof("1200000"),
            rembourse.instalments().stream()
                .map(e -> new TimedFlow(BigDecimal.valueOf(e.number()), e.total()))
                .toList());

        assertThat(periodique.signum()).isNegative();
    }

    // ------------------------------------------------------------------ plafond d'usure

    @Test
    @DisplayName("le depassement du plafond se constate sur le taux effectif, pas sur le taux nominal")
    void depassementDuPlafond() {
        Teg teg = EffectiveRate.of(echeancier(credit()), xof("20000"),
                                   RateAnnualisation.PROPORTIONAL);

        // Un credit affiche a 12 % de taux nominal depasse un plafond d'usure a 15 % des lors que
        // des frais de dossier sont preleves. C'est tout l'objet du controle.
        assertThat(teg.exceeds(new BigDecimal("15"))).isTrue();
        assertThat(teg.exceeds(new BigDecimal("16"))).isFalse();
        // Un plafond absent ne plafonne rien : tous les pays n'en imposent pas.
        assertThat(teg.exceeds(null)).isFalse();
        assertThat(teg.exceeds(BigDecimal.ZERO)).isFalse();
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("des frais qui absorbent le capital sont refuses")
    void fraisSuperieursAuCapital() {
        assertThatThrownBy(() -> EffectiveRate.of(echeancier(credit()), xof("1000000"),
                                                   RateAnnualisation.PROPORTIONAL))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("l'emprunteur ne recoit rien");
    }

    @Test
    @DisplayName("un flux de signe negatif est refuse : le sens vient de la place, pas du signe")
    void fluxNegatif() {
        assertThatThrownBy(() -> new TimedFlow(BigDecimal.ONE, xof("1000").negate()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jamais par son signe");
    }

    @Test
    @DisplayName("la resolution est deterministe : deux appels donnent le meme chiffre")
    void resolutionDeterministe() {
        // Une dichotomie a nombre d'iterations fixe et tolerance fixe rend le meme resultat a
        // chaque appel. C'est la condition pour qu'un TEG restitue au client soit reproductible
        // des annees plus tard.
        for (int essai = 0; essai < 5; essai++) {
            assertThat(EffectiveRate.of(echeancier(credit()), xof("20000"),
                                        RateAnnualisation.ACTUARIAL).annualRatePercent())
                .isEqualByComparingTo("17.101880");
        }
    }
}
