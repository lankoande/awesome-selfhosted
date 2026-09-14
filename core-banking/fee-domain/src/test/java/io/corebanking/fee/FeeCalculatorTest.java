package io.corebanking.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

    private static final UUID PRODUIT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TAXE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final LocalDate ANCRAGE = LocalDate.of(2026, 9, 1);
    private static final FeePeriod SEPTEMBRE = FeeFrequency.MONTHLY.period(ANCRAGE, 0);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static FeeTerms.Builder forfait(String montant) {
        return terms().basis(FeeBasis.FLAT).flatAmount(xof(montant));
    }

    private static FeeTerms.Builder terms() {
        return new FeeTerms.Builder("TENUE_COMPTE", "Frais de tenue de compte", Currencies.XOF)
            .frequency(FeeFrequency.MONTHLY).anchor(ANCRAGE).timing(FeeTiming.IN_ARREARS)
            .incomeAccount(PRODUIT);
    }

    @Test
    @DisplayName("forfait sans taxe : le net est le total")
    void forfaitSimple() {
        FeeAssessment liquidation =
            FeeCalculator.assess(forfait("2000").build(), SEPTEMBRE, null, SEPTEMBRE.days());

        assertThat(liquidation.net()).isEqualTo(xof("2000"));
        assertThat(liquidation.tax()).isEqualTo(xof("0"));
        assertThat(liquidation.total()).isEqualTo(xof("2000"));
    }

    @Test
    @DisplayName("la taxe porte sur le net arrondi, et le total est la somme des deux montants imputes")
    void taxeAssiseSurLeNetArrondi() {
        // 1 525 x 18 % = 274,5 -> 274 en arrondi au pair. Le total suit : 1 799.
        //
        // L'autre methode — arrondir le total toutes taxes comprises, 1 525 x 1,18 = 1 799,5 -> 1 800,
        // puis deduire la taxe par difference, 275 — donne un franc de plus au client et une base
        // declaree qui ne correspond a aucun montant comptabilise. C'est precisement ce que ce
        // calcul refuse de faire.
        FeeAssessment liquidation = FeeCalculator.assess(
            forfait("1525").taxRatePercent("18").taxAccount(TAXE).build(),
            SEPTEMBRE, null, SEPTEMBRE.days());

        assertThat(liquidation.net()).isEqualTo(xof("1525"));
        assertThat(liquidation.tax()).isEqualTo(xof("274"));
        assertThat(liquidation.total()).isEqualTo(xof("1799"));
    }

    @Test
    @DisplayName("le taux s'applique a l'assiette constatee, sans proratisation temporelle")
    void tauxSurAssiette() {
        // Commission du plus fort decouvert : 0,05 % de 12 400 000 = 6 200, applique tel quel.
        FeeAssessment liquidation = FeeCalculator.assess(
            terms().basis(FeeBasis.RATE_ON_HIGHEST_DEBIT_BALANCE).ratePercent("0.05").build(),
            SEPTEMBRE, xof("12400000"), SEPTEMBRE.days());

        assertThat(liquidation.net()).isEqualTo(xof("6200"));
    }

    @Test
    @DisplayName("le prorata reduit la commission aux jours servis")
    void prorataDesJoursServis() {
        FeeAssessment liquidation = FeeCalculator.assess(
            forfait("3000").proration(Proration.ACTUAL_DAYS).build(), SEPTEMBRE, null, 15);

        // 3 000 x 15 / 30 = 1 500.
        assertThat(liquidation.chargedDays()).isEqualTo(15);
        assertThat(liquidation.periodDays()).isEqualTo(30);
        assertThat(liquidation.net()).isEqualTo(xof("1500"));
    }

    @Test
    @DisplayName("sans proratisation, une periode partielle est due en entier")
    void sansProrata() {
        FeeAssessment liquidation = FeeCalculator.assess(
            forfait("3000").proration(Proration.NONE).build(), SEPTEMBRE, null, 15);

        assertThat(liquidation.net()).isEqualTo(xof("3000"));
    }

    @Test
    @DisplayName("le plancher s'applique apres le prorata, le plafond aussi")
    void bornesApresProrata() {
        FeeTerms conditions = forfait("3000")
            .proration(Proration.ACTUAL_DAYS).floor(xof("2000")).cap(xof("2500")).build();

        // 3 000 x 10 / 30 = 1 000, releve au plancher 2 000.
        assertThat(FeeCalculator.assess(conditions, SEPTEMBRE, null, 10).net()).isEqualTo(xof("2000"));
        // Periode pleine : 3 000 ramene au plafond 2 500.
        assertThat(FeeCalculator.assess(conditions, SEPTEMBRE, null, 30).net()).isEqualTo(xof("2500"));
    }

    @Test
    @DisplayName("aucun jour servi : rien n'est du, pas meme le plancher")
    void aucunJourServi() {
        FeeAssessment liquidation = FeeCalculator.assess(
            forfait("3000").proration(Proration.ACTUAL_DAYS).floor(xof("2000")).build(),
            SEPTEMBRE, null, 0);

        assertThat(liquidation.isEmpty()).isTrue();
        assertThat(liquidation.total()).isEqualTo(xof("0"));
    }

    @Test
    @DisplayName("bareme par tranches : les tranches s'additionnent, aucun taux moyen n'est employe")
    void baremeParTranches() {
        TieredRate bareme = new TieredRate(List.of(
            new Tier(new BigDecimal("0"), new BigDecimal("1000000"), new BigDecimal("1")),
            new Tier(new BigDecimal("1000000"), null, new BigDecimal("0.5"))),
            TieringMode.PROGRESSIVE);

        FeeAssessment liquidation = FeeCalculator.assess(
            terms().basis(FeeBasis.TIERED_ON_CLOSING_BALANCE).tiers(bareme).build(),
            SEPTEMBRE, xof("3000000"), SEPTEMBRE.days());

        // 1 000 000 a 1 % = 10 000 ; 2 000 000 a 0,5 % = 10 000. Total 20 000.
        assertThat(liquidation.net()).isEqualTo(xof("20000"));
    }

    @Test
    @DisplayName("une assiette negative est refusee : un decouvert et un solde crediteur ne se compensent pas")
    void assietteNegative() {
        assertThatThrownBy(() -> FeeCalculator.assess(
            terms().basis(FeeBasis.RATE_ON_CLOSING_BALANCE).ratePercent("1").build(),
            SEPTEMBRE, xof("-500000"), SEPTEMBRE.days()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Assiette negative");
    }

    @Test
    @DisplayName("une assiette absente n'est pas remplacee par zero")
    void assietteAbsente() {
        assertThatThrownBy(() -> FeeCalculator.assess(
            terms().basis(FeeBasis.RATE_ON_CLOSING_BALANCE).ratePercent("1").build(),
            SEPTEMBRE, null, SEPTEMBRE.days()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("equilibree et fausse");
    }

    @Test
    @DisplayName("le total est toujours la somme exacte du net impute et de la taxe imputee")
    void totalToujoursSommeDesComposantes() {
        FeeTerms conditions = terms().basis(FeeBasis.RATE_ON_CLOSING_BALANCE)
            .ratePercent("0.37").taxRatePercent("18").taxAccount(TAXE).build();

        for (long solde = 0; solde <= 5_000_000; solde += 7_331) {
            FeeAssessment liquidation = FeeCalculator.assess(
                conditions, SEPTEMBRE, Money.of(solde, Currencies.XOF), SEPTEMBRE.days());

            assertThat(liquidation.net().isBookable()).isTrue();
            assertThat(liquidation.tax().isBookable()).isTrue();
            assertThat(liquidation.total()).isEqualTo(liquidation.net().plus(liquidation.tax()));
        }
    }
}
