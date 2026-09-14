package io.corebanking.fee.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.product.ProductFamilies;
import io.corebanking.product.ProductFamily;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Accord entre ce que le module des commissions lit et ce que les familles de produit declarent.
 *
 * <p>Le fichier des familles vit dans {@code product-catalog}, dont ce module depend ; l'accord ne
 * peut donc pas etre verifie par le compilateur. Un parametre lu ici et absent de la famille serait
 * refuse a l'activation du produit qui l'emploie — et le defaut ne se verrait qu'au deploiement
 * d'un tarif, chez le client.
 */
class FeeFamilyTest {

    /** Familles auxquelles une commission peut s'appliquer. */
    private static final Set<String> PORTEUSES = Set.of("CURRENT_ACCOUNT", "SAVINGS_ACCOUNT");

    @Test
    @DisplayName("tout parametre de commission lu par le code est declare par les familles")
    void parametresDeCommissionDeclares() {
        Set<String> lus = new TreeSet<>(FeeCatalog.parameterNames(ProductFamily.PLACEHOLDER));
        lus.add(FeeCatalog.P_FEE_CODES);

        for (String famille : PORTEUSES) {
            assertThat(ProductFamilies.declaredParameters(famille))
                .as("famille " + famille)
                .containsAll(lus);
        }
    }

    @Test
    @DisplayName("le decouvert autorise n'est declare que par le compte courant")
    void decouvertReserveAuCompteCourant() {
        // Un plafond de decouvert sur un livret d'epargne serait lu par le controle de provision
        // et jamais par personne d'autre : il donnerait a croire que le livret peut passer
        // debiteur, ce que le produit n'autorise pas.
        assertThat(ProductFamilies.declaredParameters("CURRENT_ACCOUNT"))
            .contains(FeeCatalog.P_OVERDRAFT_LIMIT);
        assertThat(ProductFamilies.declaredParameters("SAVINGS_ACCOUNT"))
            .doesNotContain(FeeCatalog.P_OVERDRAFT_LIMIT);
    }
}
