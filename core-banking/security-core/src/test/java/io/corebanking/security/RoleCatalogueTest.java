package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.security.json.Json;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoleCatalogueTest {

    @Test
    @DisplayName("le catalogue se charge et porte les cinq champs attendus")
    void the_catalogue_carries_the_declared_fields() {
        RoleDefinition guichetier = RoleCatalogue.require(Roles.TELLER);

        assertThat(guichetier.code()).isEqualTo("teller");
        assertThat(guichetier.name()).isEqualTo("Guichetier");
        assertThat(guichetier.description()).contains("Operations de caisse");
        assertThat(guichetier.isSystem()).isTrue();
        assertThat(guichetier.category()).isEqualTo("RESEAU");
    }

    @Test
    @DisplayName("les champs hors du socle sont portes en attributs libres")
    void extra_fields_are_carried_as_attributes() {
        assertThat(RoleCatalogue.require(Roles.TELLER).attributes())
            .containsEntry("reviewFrequencyMonths", "6")
            .containsEntry("requiresBranch", "true");
    }

    @Test
    @DisplayName("catalogue et politique se recouvrent exactement")
    void catalogue_and_policy_match_exactly() {
        assertThatCode(RoleCatalogue::validateAgainstPolicy).doesNotThrowAnyException();
        assertThat(RoleCatalogue.declared()).isEqualTo(RoleCatalogue.usedByPolicy());
    }

    @Test
    @DisplayName("tout role du catalogue est un role systeme livre avec le produit")
    void every_catalogue_role_is_a_system_role() {
        assertThat(RoleCatalogue.systemRoles()).hasSize(RoleCatalogue.declared().size());
    }

    @Test
    @DisplayName("la categorie regroupe les roles pour la revue d'habilitations")
    void categories_group_roles_for_review() {
        Map<String, java.util.List<RoleDefinition>> parCategorie = RoleCatalogue.byCategory();

        assertThat(parCategorie).containsKeys("RESEAU", "SIEGE", "PARAMETRAGE", "EXPLOITATION",
                                              "CONTROLE");
        assertThat(parCategorie.get("RESEAU")).extracting(RoleDefinition::code)
            .containsExactlyInAnyOrder(Roles.TELLER, Roles.BRANCH_MANAGER, Roles.CUSTOMER_OFFICER);
    }

    @Test
    @DisplayName("l'exclusivite est declarative : elle produit la regle de segregation")
    void exclusivity_is_declarative() {
        assertThat(RoleCatalogue.exclusiveRoles()).containsExactly(Roles.AUDITOR);

        // La regle de SecurityConfig en decoule : aucun cumul avec l'auditeur.
        assertThat(SecurityConfig.segregationConflict(
            java.util.Set.of(Roles.AUDITOR, Roles.ACCOUNTANT))).isPresent();
        assertThat(SecurityConfig.segregationConflict(
            java.util.Set.of(Roles.AUDITOR))).isEmpty();
    }

    @Test
    @DisplayName("un code de role en majuscules est refuse : Keycloak en ferait un role distinct")
    void an_uppercase_code_is_refused() {
        assertThatThrownBy(() -> new RoleDefinition("Teller", "Guichetier", "", true, "RESEAU",
                                                     Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minuscules");
    }

    @Test
    @DisplayName("l'ordre du fichier est preserve : le provisionnement engendre est reproductible")
    void the_declaration_order_is_preserved() {
        assertThat(RoleCatalogue.definitions().keySet())
            .containsExactly(Roles.TELLER, Roles.BRANCH_MANAGER, Roles.CUSTOMER_OFFICER,
                             Roles.ACCOUNTANT, Roles.PRODUCT_MANAGER, Roles.RISK_OFFICER,
                             Roles.OPERATOR, Roles.AUDITOR);

        // Deux generations successives donnent un fichier identique : le diff d'une livraison ne
        // montre que ce qui a reellement change.
        assertThat(KeycloakProvisioning.partialImport())
            .isEqualTo(KeycloakProvisioning.partialImport());
    }

    @Test
    @DisplayName("le lecteur JSON refuse un document tronque plutot que de deviner")
    void the_json_reader_refuses_a_truncated_document() {
        assertThatThrownBy(() -> Json.parseObject("{ \"client\": \"core-banking\", \"roles\": ["))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("tronque");
        assertThatThrownBy(() -> Json.parseObject("{ \"a\": 1 } parasite"))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("Contenu inattendu");
    }

    @Test
    @DisplayName("le lecteur JSON restitue objets, tableaux, chaines, nombres et booleens")
    void the_json_reader_covers_the_needed_subset() {
        Map<String, Object> document = Json.parseObject("""
            { "client": "core-banking",
              "roles": [ { "code": "teller", "isSystem": true, "rank": 1.5, "parent": null } ] }
            """);

        assertThat(document.get("client")).isEqualTo("core-banking");
        assertThat(document.get("roles")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }
}
