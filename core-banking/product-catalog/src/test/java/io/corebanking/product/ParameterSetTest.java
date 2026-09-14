package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParameterSetTest {

    private static final ParameterSet EPARGNE = new ParameterSet(
        "EP-LIVRET", Map.of("interest.rate", "3,5", "interest.day_count", "ACT_365"));

    @Test
    @DisplayName("un parametre absent nomme le produit et le parametre")
    void parametreAbsentNomme() {
        // Le controle de famille ferme la porte du deploiement ; celle-ci reste ouverte sur un
        // parametrage corrige en base apres coup. Le message doit alors suffire a savoir quel
        // produit reparer, sans avoir a remonter la pile d'un traitement de nuit.
        assertThatThrownBy(() -> EPARGNE.requireUuid("interest.credit_account"))
            .isInstanceOf(ParameterSet.MissingParameterException.class)
            .hasMessageContaining("interest.credit_account")
            .hasMessageContaining("EP-LIVRET");
    }

    @Test
    @DisplayName("un parametre present mais inexploitable est distingue d'un parametre absent")
    void parametreInexploitable() {
        // La virgule decimale est une saisie francaise plausible, et un repli silencieux sur zero
        // remunererait tout un portefeuille a taux nul.
        assertThatThrownBy(() -> EPARGNE.requireDecimal("interest.rate"))
            .isInstanceOf(ParameterSet.InvalidParameterException.class)
            .hasMessageContaining("3,5");

        assertThat(EPARGNE.requireString("interest.day_count")).isEqualTo("ACT_365");
        assertThat(EPARGNE.has("interest.side")).isFalse();
    }
}
