package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskGridTest {

    private static RiskBucket classe(int rang, String code, int de, Integer a, String taux,
                                     boolean sain) {
        return new RiskBucket(rang, code, code, de, a, new BigDecimal(taux), sain);
    }

    /**
     * Grille d'allure UEMOA. Les valeurs numeriques sont illustratives : le profil reglementaire
     * reel est parametre, et ses seuils comme ses taux relevent de l'instruction en vigueur.
     */
    private static RiskGrid grille() {
        return new RiskGrid("UEMOA-ILLUSTRATIF", List.of(
            classe(0, "SAIN", 0, 29, "0", true),
            classe(1, "IMPAYE", 30, 89, "0", true),
            classe(2, "DOUTEUX", 90, 179, "20", false),
            classe(3, "COMPROMIS", 180, null, "100", false)),
            Contagion.CUSTOMER, "DOUTEUX");
    }

    @Test
    @DisplayName("la classe suit le nombre de jours de retard")
    void classement() {
        RiskGrid grille = grille();
        assertThat(grille.bucketFor(0).code()).isEqualTo("SAIN");
        assertThat(grille.bucketFor(29).code()).isEqualTo("SAIN");
        assertThat(grille.bucketFor(30).code()).isEqualTo("IMPAYE");
        assertThat(grille.bucketFor(90).code()).isEqualTo("DOUTEUX");
        assertThat(grille.bucketFor(10_000).code()).isEqualTo("COMPROMIS");
    }

    @Test
    @DisplayName("la suspension des interets s'applique a partir de la classe designee, et au-dela")
    void suspension() {
        RiskGrid grille = grille();
        assertThat(grille.suspendsAt(grille.bucketFor(29))).isFalse();
        assertThat(grille.suspendsAt(grille.bucketFor(89))).isFalse();
        assertThat(grille.suspendsAt(grille.bucketFor(90))).isTrue();
        assertThat(grille.suspendsAt(grille.bucketFor(400))).isTrue();
    }

    @Test
    @DisplayName("la contagion retient la classe la plus degradee")
    void contagion() {
        RiskGrid grille = grille();
        assertThat(grille.worst(grille.bucketFor(0), grille.bucketFor(200)).code())
            .isEqualTo("COMPROMIS");
        assertThat(grille.worst(grille.bucketFor(200), grille.bucketFor(0)).code())
            .isEqualTo("COMPROMIS");
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("un trou entre deux classes est refuse : un credit n'y serait classe nulle part")
    void trouDansLaGrille() {
        assertThatThrownBy(() -> new RiskGrid("TROUEE", List.of(
            classe(0, "SAIN", 0, 29, "0", true),
            classe(1, "DOUTEUX", 90, null, "20", false)), Contagion.NONE, null))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("de 30 a 89 jours ne sont couverts par aucune classe");
    }

    @Test
    @DisplayName("un taux de provision qui decroit avec la degradation est refuse")
    void tauxDecroissant() {
        // Se lit comme une inversion de deux lignes dans un tableur, et n'a aucun sens prudentiel.
        assertThatThrownBy(() -> new RiskGrid("INVERSEE", List.of(
            classe(0, "SAIN", 0, 89, "0", true),
            classe(1, "DOUTEUX", 90, 179, "50", false),
            classe(2, "COMPROMIS", 180, null, "20", false)), Contagion.NONE, null))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("ne provisionne pas moins");
    }

    @Test
    @DisplayName("une classe saine apres une classe en souffrance est refusee")
    void frontiereInversee() {
        assertThatThrownBy(() -> new RiskGrid("ALTERNEE", List.of(
            classe(0, "SAIN", 0, 89, "0", true),
            classe(1, "DOUTEUX", 90, 179, "20", false),
            classe(2, "ENCORE_SAIN", 180, null, "20", true)), Contagion.NONE, null))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("ne s'inverse pas");
    }

    @Test
    @DisplayName("une derniere classe bornee est refusee : au-dela, aucun credit ne serait classe")
    void derniereClasseBornee() {
        assertThatThrownBy(() -> new RiskGrid("BORNEE", List.of(
            classe(0, "SAIN", 0, 89, "0", true),
            classe(1, "DOUTEUX", 90, 179, "20", false)), Contagion.NONE, null))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("aucun credit ne serait classe");
    }

    @Test
    @DisplayName("une premiere classe qui ne part pas de zero est refusee")
    void premiereClasseDecalee() {
        assertThatThrownBy(() -> new RiskGrid("DECALEE", List.of(
            classe(0, "IMPAYE", 30, null, "20", false)), Contagion.NONE, null))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("un credit a jour ne serait classe nulle part");
    }

    @Test
    @DisplayName("un seuil de suspension qui ne designe aucune classe est refuse")
    void seuilDeSuspensionInconnu() {
        assertThatThrownBy(() -> new RiskGrid("INCONNUE", List.of(
            classe(0, "SAIN", 0, null, "0", true)), Contagion.NONE, "DOUTEUX"))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("ne figure pas dans la grille");
    }

    @Test
    @DisplayName("un taux de provision hors de [0..100] est refuse")
    void tauxHorsBornes() {
        assertThatThrownBy(() -> classe(0, "ABSURDE", 0, null, "150", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hors de [0..100]");
    }

    // ------------------------------------------------------------------ provision

    @Test
    @DisplayName("la provision porte sur l'encours diminue des garanties retenues")
    void provisionSurAssietteNette() {
        var provision = Provisioning.compute(
            Money.of("10000000", Currencies.XOF), Money.of("4000000", Currencies.XOF),
            grille().bucketFor(120));

        assertThat(provision.base()).isEqualTo(Money.of("6000000", Currencies.XOF));
        assertThat(provision.amount()).isEqualTo(Money.of("1200000", Currencies.XOF));
    }

    @Test
    @DisplayName("une garantie ne couvre pas au-dela de ce qu'elle garantit")
    void garantieSurevaluee() {
        // Sans ce plafond, une surete surevaluee produirait une assiette negative, donc une
        // reprise de provision sur un credit en souffrance.
        var provision = Provisioning.compute(
            Money.of("1000000", Currencies.XOF), Money.of("9000000", Currencies.XOF),
            grille().bucketFor(200));

        assertThat(provision.retainedCollateral()).isEqualTo(Money.of("1000000", Currencies.XOF));
        assertThat(provision.base().isZero()).isTrue();
        assertThat(provision.amount().isZero()).isTrue();
    }

    @Test
    @DisplayName("la variation a comptabiliser est la difference avec la provision deja constituee")
    void variationDeProvision() {
        var provision = Provisioning.compute(
            Money.of("1000000", Currencies.XOF), null, grille().bucketFor(200));

        assertThat(provision.amount()).isEqualTo(Money.of("1000000", Currencies.XOF));
        assertThat(provision.deltaFrom(Money.of("200000", Currencies.XOF)))
            .isEqualTo(Money.of("800000", Currencies.XOF));
        // Une provision qui diminue est une reprise, et le signe le dit.
        assertThat(provision.deltaFrom(Money.of("1500000", Currencies.XOF)).isNegative()).isTrue();
    }
}
