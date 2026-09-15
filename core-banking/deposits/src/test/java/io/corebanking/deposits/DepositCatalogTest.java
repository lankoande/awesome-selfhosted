package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.product.ProductFamilies;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les parametres que le module lit sont ceux que les familles de depot declarent. */
class DepositCatalogTest {

    @Test
    @DisplayName("chaque constante de DepositCatalog est declaree par les deux familles de depot")
    void constantesEtFamillesSAccordent() {
        Set<String> lus = new TreeSet<>();
        for (Field field : DepositCatalog.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("P_")) {
                try {
                    lus.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        assertThat(lus).isNotEmpty();
        for (String famille : Set.of("CURRENT_ACCOUNT", "SAVINGS_ACCOUNT")) {
            assertThat(ProductFamilies.declaredParameters(famille))
                .as("famille " + famille).containsAll(lus);
        }
        // Un parametre lu par le module de credit n'est pas un parametre de depot.
        assertThat(ProductFamilies.declaredParameters("TERM_LOAN"))
            .doesNotContainAnyElementsOf(lus);
    }
}
