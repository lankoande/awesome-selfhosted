package io.corebanking.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.schema.expr.EvaluationContext;
import io.corebanking.schema.expr.ExpressionException;
import io.corebanking.schema.expr.Expressions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpressionsTest {

    private final EvaluationContext contexte = EvaluationContext.builder()
        .put("base", "1000000")
        .put("taux", "0.18")
        .put("exonere", "0")
        .build();

    @Test
    @DisplayName("les quatre operations et les parentheses sont exactes")
    void arithmetic_is_exact() {
        assertThat(Expressions.parse("base * taux").asNumber(contexte))
            .isEqualByComparingTo("180000.00");
        assertThat(Expressions.parse("base + base * taux").asNumber(contexte))
            .isEqualByComparingTo("1180000.00");
        assertThat(Expressions.parse("(base + 1) * 2").asNumber(contexte))
            .isEqualByComparingTo("2000002");
        assertThat(Expressions.parse("-base").asNumber(contexte))
            .isEqualByComparingTo("-1000000");
    }

    @Test
    @DisplayName("round, abs, min et max sont disponibles ; rien d'autre")
    void the_function_set_is_deliberately_small() {
        assertThat(Expressions.parse("round(base * taux / 7, 0)").asNumber(contexte))
            .isEqualByComparingTo("25714");
        assertThat(Expressions.parse("abs(0 - base)").asNumber(contexte))
            .isEqualByComparingTo("1000000");
        assertThat(Expressions.parse("min(base, 500)").asNumber(contexte))
            .isEqualByComparingTo("500");
        assertThat(Expressions.parse("max(base, 500)").asNumber(contexte))
            .isEqualByComparingTo("1000000");

        // Le nom et l'arite sont verifies a l'analyse : le schema est refuse au deploiement.
        assertThatThrownBy(() -> Expressions.parse("sqrt(base)"))
            .isInstanceOf(ExpressionException.class)
            .hasMessageContaining("Fonction inconnue");
        assertThatThrownBy(() -> Expressions.parse("round(base)"))
            .isInstanceOf(ExpressionException.class)
            .hasMessageContaining("attend 2 argument(s)");
    }

    @Test
    @DisplayName("les conditions combinent comparaisons et operateurs logiques")
    void conditions_combine_comparisons() {
        assertThat(Expressions.parse("base > 0").asBoolean(contexte)).isTrue();
        assertThat(Expressions.parse("exonere == 0 and base > 100").asBoolean(contexte)).isTrue();
        assertThat(Expressions.parse("not (base > 100)").asBoolean(contexte)).isFalse();
        assertThat(Expressions.parse("base < 10 or taux >= 0.18").asBoolean(contexte)).isTrue();
    }

    @Test
    @DisplayName("une variable absente est nommee, et n'est jamais remplacee par zero")
    void a_missing_variable_is_never_defaulted() {
        assertThatThrownBy(() -> Expressions.parse("assiette * taux").asNumber(contexte))
            .isInstanceOf(ExpressionException.class)
            .hasMessageContaining("assiette")
            .hasMessageContaining("Aucune valeur par defaut");
    }

    @Test
    @DisplayName("une division par zero est refusee, pas silencieusement neutralisee")
    void division_by_zero_is_refused() {
        assertThatThrownBy(() -> Expressions.parse("base / exonere").asNumber(contexte))
            .isInstanceOf(ExpressionException.class)
            .hasMessageContaining("Division par zero");
    }

    @Test
    @DisplayName("une syntaxe fautive est rejetee a la compilation du schema, avec sa position")
    void a_syntax_error_is_located() {
        assertThatThrownBy(() -> Expressions.parse("base * * 2"))
            .isInstanceOf(ExpressionException.class)
            .hasMessageContaining("position");
        assertThatThrownBy(() -> Expressions.parse("base + "))
            .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> Expressions.parse("(base + 1"))
            .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("le langage n'offre aucun moyen d'appeler du code arbitraire")
    void the_language_cannot_reach_outside() {
        // Ni acces a une classe, ni appel de methode, ni chaine de caracteres : le seul vocabulaire
        // est celui des nombres et des variables declarees.
        assertThatThrownBy(() -> Expressions.parse("System.exit(0)"))
            .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> Expressions.parse("\"texte\""))
            .isInstanceOf(ExpressionException.class);
    }

    @Test
    @DisplayName("les variables referencees sont connues du schema, pour permettre le tirage")
    void referenced_variables_are_exposed() {
        assertThat(Expressions.parse("round(base * taux, 0) + frais").variables())
            .containsExactlyInAnyOrder("base", "taux", "frais");
    }
}
