package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.product.ProductFamilies;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accord entre ce que le module de credit lit et ce que la famille {@code TERM_LOAN} declare.
 *
 * <p>Le fichier des familles vit dans {@code product-catalog}, dont ce module depend : l'accord ne
 * peut pas etre verifie par le compilateur. Une constante ajoutee ici et oubliee la-bas ferait
 * refuser a l'activation tout produit qui l'emploie ; l'inverse laisserait un parametre declare que
 * personne ne lit, et celui qui le renseigne croirait avoir parametre quelque chose.
 */
class LoanFamilyTest {

    @Test
    @DisplayName("tout parametre de credit lu par le code est declare par la famille TERM_LOAN")
    void parametresDeCreditDeclares() {
        assertThat(ProductFamilies.declaredParameters("TERM_LOAN"))
            .containsAll(constantes());
    }

    @Test
    @DisplayName("la famille TERM_LOAN ne declare aucun parametre que personne ne lit")
    void aucunParametreMort() {
        assertThat(ProductFamilies.declaredParameters("TERM_LOAN"))
            .containsExactlyInAnyOrderElementsOf(constantes());
    }

    private static Set<String> constantes() {
        Set<String> names = new TreeSet<>();
        for (Field field : LoanCatalog.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                && field.getName().startsWith("P_")) {
                try {
                    field.setAccessible(true);
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(field.getName(), e);
                }
            }
        }
        return names;
    }
}
