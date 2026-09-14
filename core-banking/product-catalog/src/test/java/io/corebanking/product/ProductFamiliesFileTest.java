package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Coherence du fichier des familles, verifiee au chargement des classes.
 *
 * <p>Meme regime que le catalogue des roles : un fichier incoherent fait echouer le demarrage, et
 * non le premier deploiement de paramétrage. Une condition qui ne se declenche jamais ne se
 * signale d'aucune autre facon — elle laisse simplement passer ce qu'elle etait censee refuser.
 */
class ProductFamiliesFileTest {

    private static void refuse(String json, String detail) {
        assertThatThrownBy(() -> ProductFamilies.parse(json))
            .isInstanceOf(ProductFamilies.CatalogueException.class)
            .hasMessageContaining(detail);
    }

    @Test
    @DisplayName("une condition sans declencheur est refusee")
    void conditionSansDeclencheur() {
        refuse("""
            { "families": [ { "code": "F", "label": "F", "conditions": [
                { "when": "a", "require": ["b"], "because": "..." } ] } ] }
            """, "indiquer soit « in », soit « present »");

        // Les deux a la fois : la condition se declencherait sur la presence et sur la valeur, et
        // personne ne saurait laquelle des deux a joue.
        refuse("""
            { "families": [ { "code": "F", "label": "F", "conditions": [
                { "when": "a", "in": ["X"], "present": true, "require": ["b"],
                  "because": "..." } ] } ] }
            """, "indiquer soit « in », soit « present »");
    }

    @Test
    @DisplayName("une condition qui n'exige rien est refusee")
    void conditionSansExigence() {
        refuse("""
            { "families": [ { "code": "F", "label": "F", "conditions": [
                { "when": "a", "present": true, "because": "..." } ] } ] }
            """, "elle n'exige rien");
    }

    @Test
    @DisplayName("un parametre a la fois obligatoire et facultatif est refuse")
    void obligatoireEtFacultatif() {
        refuse("""
            { "families": [ { "code": "F", "label": "F",
                "required": ["a"], "optional": ["a"] } ] }
            """, "a la fois obligatoire et facultatif");
    }

    @Test
    @DisplayName("un marqueur hors bloc repete est refuse, et son absence dans un bloc aussi")
    void marqueurMalPlace() {
        // Hors bloc repete, rien ne le remplacerait.
        refuse("""
            { "families": [ { "code": "F", "label": "F", "required": ["fee.{code}.amount"] } ] }
            """, "hors d'un bloc repete");

        // Dans un bloc repete, son absence ferait designer le meme parametre pour tous les
        // elements : une seule commission suffirait a satisfaire toutes les autres.
        refuse("""
            { "families": [ { "code": "F", "label": "F", "groups": [
                { "listParameter": "fee.codes", "required": ["fee.amount"] } ] } ] }
            """, "sans porter le marqueur");
    }

    @Test
    @DisplayName("une alternative a un seul terme est refusee")
    void alternativeADeuxTermes() {
        refuse("""
            { "families": [ { "code": "F", "label": "F", "requireOneOf": [
                { "of": ["a"], "because": "..." } ] } ] }
            """, "n'est pas une alternative, c'est une exigence");
    }

    @Test
    @DisplayName("un bloc inconnu et une famille declaree deux fois sont refuses")
    void blocInconnuEtDoublon() {
        refuse("""
            { "families": [ { "code": "F", "label": "F", "includes": ["interets"] } ] }
            """, "bloc inconnu");

        refuse("""
            { "families": [ { "code": "F", "label": "F" }, { "code": "F", "label": "F" } ] }
            """, "Famille declaree deux fois");
    }
}
