package io.corebanking.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CollateralValuationTest {

    private static final LocalDate ARRETE = LocalDate.of(2027, 1, 13);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    /** Hypotheque retenue a 50 %, expertise valable trois ans ; caution bancaire a 100 %. */
    private static Map<String, CollateralPolicy> politiques() {
        return Map.of(
            "HYPOTHEQUE", new CollateralPolicy("HYPOTHEQUE", "Hypotheque",
                                               new BigDecimal("50"), 36),
            "CAUTION_BANCAIRE", new CollateralPolicy("CAUTION_BANCAIRE", "Caution bancaire",
                                                     new BigDecimal("100"), 0));
    }

    private static CollateralCharge surete(String actif, String type, String valeurActif,
                                           String garanti, int rang, LocalDate expertise,
                                           String quotePart) {
        return new CollateralCharge(UUID.randomUUID(), actif, type, xof(valeurActif),
                                    xof(garanti), rang, expertise, new BigDecimal(quotePart));
    }

    private static Coverage evaluer(List<CollateralValuation.Charged> suretes) {
        return CollateralValuation.evaluate(suretes, politiques(), ARRETE, Currencies.XOF);
    }

    // ------------------------------------------------------------------ les quatre reductions

    @Test
    @DisplayName("la quotite vient du type de surete, jamais de la saisie")
    void quotiteDuReferentiel() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-1", "HYPOTHEQUE", "30000000", "10000000", 1,
                   LocalDate.of(2026, 6, 1), "100"),
            Money.zero(Currencies.XOF))));

        // 10 M garantis, retenus a 50 % : 5 M eligibles. Laisser saisir la quotite reviendrait a
        // laisser un agent decider du niveau de provision de son propre portefeuille.
        assertThat(couverture.eligible()).isEqualTo(xof("5000000"));
        assertThat(couverture.excluded()).isEmpty();
    }

    @Test
    @DisplayName("une surete ne couvre pas au-dela de ce qu'elle inscrit")
    void montantGarantiPlafonnant() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-1", "HYPOTHEQUE", "30000000", "10000000", 1,
                   LocalDate.of(2026, 6, 1), "100"),
            Money.zero(Currencies.XOF))));

        // L'immeuble vaut 30 M, l'hypotheque en inscrit 10 : c'est 10 qui comptent, pas 30.
        assertThat(couverture.lines().get(0).retained()).isEqualTo(xof("10000000"));
    }

    @Test
    @DisplayName("un second rang n'est couvert que par ce que le premier laisse")
    void secondRang() {
        // Immeuble a 12 M, premier rang de 10 M au profit d'une autre banque : il reste 2 M.
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-2", "HYPOTHEQUE", "12000000", "8000000", 2,
                   LocalDate.of(2026, 6, 1), "100"),
            xof("10000000"))));

        // 2 M disponibles, retenus a 50 % : 1 M. Ignorer le rang compterait 8 M garantis sur un
        // actif deja grev0e, soit quatre millions de provision en moins pour rien.
        assertThat(couverture.eligible()).isEqualTo(xof("1000000"));
        assertThat(couverture.lines().get(0).retained()).isEqualTo(xof("2000000"));
    }

    @Test
    @DisplayName("un rang integralement absorbe est ecarte, et le motif remonte")
    void rangAbsorbe() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-3", "HYPOTHEQUE", "10000000", "5000000", 3,
                   LocalDate.of(2026, 6, 1), "100"),
            xof("12000000"))));

        assertThat(couverture.eligible().isZero()).isTrue();
        assertThat(couverture.excluded()).hasSize(1);
        assertThat(couverture.excluded().get(0).exclusion()).contains("integralement absorbe");
    }

    @Test
    @DisplayName("une surete partagee entre deux credits n'est comptee qu'a sa quote-part")
    void quotePart() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-4", "HYPOTHEQUE", "30000000", "10000000", 1,
                   LocalDate.of(2026, 6, 1), "60"),
            Money.zero(Currencies.XOF))));

        // 10 M garantis, 60 % affectes a ce credit, quotite de 50 % : 3 M. La compter en entier
        // sur chacun des credits diviserait la provision du client par le nombre de ses credits.
        assertThat(couverture.eligible()).isEqualTo(xof("3000000"));
    }

    // ------------------------------------------------------------------ exclusions

    @Test
    @DisplayName("une expertise perimee ecarte la surete, et le motif remonte")
    void expertisePerimee() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("IMM-5", "HYPOTHEQUE", "30000000", "10000000", 1,
                   LocalDate.of(2023, 1, 1), "100"),
            Money.zero(Currencies.XOF))));

        // Une valeur d'il y a quatre ans n'est pas une valeur. Une garantie silencieusement exclue
        // laisserait croire a une couverture qui n'existe pas, et cela ne se decouvrirait qu'a la
        // realisation.
        assertThat(couverture.eligible().isZero()).isTrue();
        assertThat(couverture.excluded().get(0).exclusion()).contains("perimee");
    }

    @Test
    @DisplayName("une surete qui ne se revalorise pas n'a pas d'expertise a perimer")
    void sansRevalorisation() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("CAU-1", "CAUTION_BANCAIRE", "5000000", "5000000", 1, null, "100"),
            Money.zero(Currencies.XOF))));

        // Une caution bancaire ou un nantissement d'especes ne se revalorise pas : l'anciennete
        // maximale a zero le dit, et la surete reste eligible sans expertise.
        assertThat(couverture.eligible()).isEqualTo(xof("5000000"));
    }

    @Test
    @DisplayName("un type de surete sans quotite parametree est ecarte, jamais retenu a cent pour cent")
    void typeInconnu() {
        Coverage couverture = evaluer(List.of(new CollateralValuation.Charged(
            surete("GAG-1", "GAGE_SUR_STOCK", "8000000", "8000000", 1,
                   LocalDate.of(2026, 6, 1), "100"),
            Money.zero(Currencies.XOF))));

        assertThat(couverture.eligible().isZero()).isTrue();
        assertThat(couverture.excluded().get(0).exclusion())
            .contains("aucune quotite parametree");
    }

    @Test
    @DisplayName("les suretes s'additionnent, chacune reduite pour son propre compte")
    void plusieursSuretes() {
        Coverage couverture = evaluer(List.of(
            new CollateralValuation.Charged(
                surete("IMM-6", "HYPOTHEQUE", "30000000", "10000000", 1,
                       LocalDate.of(2026, 6, 1), "100"),
                Money.zero(Currencies.XOF)),
            new CollateralValuation.Charged(
                surete("CAU-2", "CAUTION_BANCAIRE", "2000000", "2000000", 1, null, "100"),
                Money.zero(Currencies.XOF))));

        assertThat(couverture.eligible()).isEqualTo(xof("7000000"));
        assertThat(couverture.lines()).hasSize(2);
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("une quotite au-dela de cent pour cent est refusee")
    void quotiteAberrante() {
        assertThatThrownBy(() -> new CollateralPolicy("X", "X", new BigDecimal("120"), 12))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("couvrirait plus que sa propre valeur");
    }

    @Test
    @DisplayName("une quote-part nulle ou superieure a cent pour cent est refusee")
    void quotePartAberrante() {
        assertThatThrownBy(() -> surete("IMM-7", "HYPOTHEQUE", "1000", "1000", 1, ARRETE, "0"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> surete("IMM-7", "HYPOTHEQUE", "1000", "1000", 1, ARRETE, "150"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
