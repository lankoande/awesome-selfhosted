package io.corebanking.api.usecase;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.fee.service.FeeSchemas;
import io.corebanking.loan.service.LoanSchemas;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le catalogue de ce que le socle comptabilise, et la barriere qu'il pose.
 */
class StandardSchemasTest {

    private StandardSchemas.Event event(String eventType) {
        return StandardSchemas.all(XOF).stream()
            .filter(e -> e.eventType().equals(eventType))
            .findFirst().orElseThrow();
    }

    /**
     * Le fichier de libelles et les registres des modules doivent se recouvrir exactement. Un
     * libelle sans modele designerait un evenement qui n'existe plus ; un modele sans libelle
     * ferait echouer le chargement. Les deux sens sont verifies ici, pas seulement le premier.
     */
    @Test
    @DisplayName("chaque modele a son libelle, et chaque libelle son modele")
    void labels_and_templates_agree() {
        Set<String> templates = StandardSchemas.templates(XOF).keySet();
        assertThat(StandardSchemas.declared()).containsExactlyInAnyOrderElementsOf(templates);
    }

    /**
     * Le piege que ce catalogue ferme : un schema redige pour un evenement que le socle impute
     * lui-meme s'activait a deux et n'etait lu par personne. Celui qui l'avait redige croyait
     * l'imputation changee.
     */
    @Test
    @DisplayName("un evenement impute par le socle est refuse a la redaction, avec sa raison")
    void refuses_a_socle_event() {
        assertThatThrownBy(() ->
            StandardSchemas.requireOverridable(Set.of(LoanSchemas.EVENT_DISBURSEMENT)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("impute par le socle")
            .hasMessageContaining(FeeSchemas.EVENT_FEE_CHARGE);
    }

    @Test
    @DisplayName("un evenement qu'aucun module ne publie est refuse : le schema ne serait jamais lu")
    void refuses_an_unknown_event() {
        assertThatThrownBy(() -> StandardSchemas.requireOverridable(Set.of("FEE_CHRGE")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inconnu du socle");
    }

    @Test
    @DisplayName("la commission est le seul evenement parametrable aujourd'hui")
    void only_the_fee_is_overridable() {
        assertThat(StandardSchemas.overridable())
            .containsExactly(FeeSchemas.EVENT_FEE_CHARGE);
        assertThat(event(FeeSchemas.EVENT_FEE_CHARGE).source())
            .isEqualTo(StandardSchemas.SOURCE_PARAMETRABLE);
        assertThat(event(FeeSchemas.EVENT_FEE_CHARGE).schemaCode())
            .isEqualTo(FeeSchemas.STANDARD_CODE);
        assertThat(event(LoanSchemas.EVENT_DISBURSEMENT).source())
            .isEqualTo(StandardSchemas.SOURCE_SOCLE);
        assertThat(event(LoanSchemas.EVENT_DISBURSEMENT).schemaCode()).isNull();
    }

    /**
     * Les grandeurs et les roles sont deduits des modeles, jamais saisis a cote. Une liste tenue a
     * la main finirait par ne plus decrire le schema qu'elle accompagne.
     */
    @Test
    @DisplayName("variables et roles sont deduits du modele lui-meme")
    void variables_and_roles_come_from_the_template() {
        StandardSchemas.Event fee = event(FeeSchemas.EVENT_FEE_CHARGE);
        assertThat(fee.variables()).containsExactlyInAnyOrder("net", "tax");
        assertThat(fee.roles())
            .containsExactlyInAnyOrder(FeeSchemas.ROLE_INCOME, FeeSchemas.ROLE_TAX);
        assertThat(fee.lines()).hasSize(3);
        assertThat(fee.derivations()).isNotEmpty();
    }

    @Test
    @DisplayName("chaque evenement porte un libelle et un module lisibles")
    void every_event_is_readable() {
        List<StandardSchemas.Event> events = StandardSchemas.all(XOF);
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.label()).isNotBlank();
            assertThat(event.moduleLabel()).isNotBlank().isNotEqualTo(event.module());
            assertThat(event.lines()).hasSizeGreaterThanOrEqualTo(2);
        });
    }
}
