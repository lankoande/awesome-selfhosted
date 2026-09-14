package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeycloakCallerFactoryTest {

    private static final String ENTITE = "11111111-1111-1111-1111-111111111111";
    private static final String AGENCE = "22222222-2222-2222-2222-222222222222";

    private final KeycloakCallerFactory factory = new KeycloakCallerFactory("core-banking");

    private Map<String, Object> claims(Map<String, Object> extra) {
        Map<String, Object> base = new java.util.HashMap<>(Map.of(
            "sub", "a1b2c3",
            "preferred_username", "k.toure",
            "legal_entity", ENTITE,
            "branch", AGENCE));
        base.putAll(extra);
        return base;
    }

    @Test
    @DisplayName("seuls les roles du client backend portent les habilitations")
    void only_backend_client_roles_are_kept() {
        Caller caller = factory.from(claims(Map.of(
            "resource_access", Map.of(
                "core-banking", Map.of("roles", List.of(Roles.TELLER, Roles.CUSTOMER_OFFICER))))));

        assertThat(caller.roles()).containsExactlyInAnyOrder(Roles.TELLER, Roles.CUSTOMER_OFFICER);
        assertThat(caller.legalEntityId()).isEqualTo(UUID.fromString(ENTITE));
        assertThat(caller.branchId()).isEqualTo(UUID.fromString(AGENCE));
        assertThat(caller.username()).isEqualTo("k.toure");
    }

    @Test
    @DisplayName("un role de royaume est ignore, meme s'il porte le nom d'un role metier")
    void a_realm_role_is_ignored_even_when_it_looks_legitimate() {
        Caller caller = factory.from(claims(Map.of(
            "realm_access", Map.of("roles", List.of(Roles.TELLER, Roles.BRANCH_MANAGER)),
            "resource_access", Map.of(
                "core-banking", Map.of("roles", List.of(Roles.CUSTOMER_OFFICER))))));

        // Un role de royaume est visible de toutes les applications du royaume. L'honorer ici
        // laisserait une habilitation definie hors du perimetre bancaire ouvrir un droit bancaire.
        assertThat(caller.roles()).containsExactly(Roles.CUSTOMER_OFFICER);
    }

    @Test
    @DisplayName("un role de royaume declare transverse est accepte, et lui seul")
    void a_realm_role_declared_cross_application_is_accepted() {
        var avecAuditeur = new KeycloakCallerFactory("core-banking", java.util.Set.of(Roles.AUDITOR));

        Caller caller = avecAuditeur.from(claims(Map.of(
            "realm_access", Map.of("roles", List.of(Roles.AUDITOR, Roles.BRANCH_MANAGER)))));

        assertThat(caller.roles()).containsExactly(Roles.AUDITOR);
    }

    @Test
    @DisplayName("un client backend non renseigne est refuse : aucune habilitation ne serait lisible")
    void a_missing_client_id_is_refused() {
        assertThatThrownBy(() -> new KeycloakCallerFactory(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("client backend obligatoire");
    }

    @Test
    @DisplayName("les roles detenus sur un autre client ne franchissent pas la frontiere")
    void roles_from_other_clients_are_ignored() {
        Caller caller = factory.from(claims(Map.of(
            "resource_access", Map.of(
                "core-banking", Map.of("roles", List.of(Roles.TELLER)),
                "un-autre-applicatif", Map.of("roles", List.of(Roles.BRANCH_MANAGER))))));

        // Une habilitation accordee sur une autre application n'ouvre aucun droit ici.
        assertThat(caller.roles()).containsExactly(Roles.TELLER);
    }

    @Test
    @DisplayName("les roles techniques de Keycloak sont ecartes, y compris s'ils sont declares transverses")
    void technical_roles_are_dropped() {
        var permissif = new KeycloakCallerFactory("core-banking",
            java.util.Set.of("offline_access", "uma_authorization", "default-roles-bank",
                             Roles.AUDITOR));

        Caller caller = permissif.from(claims(Map.of(
            "realm_access", Map.of("roles",
                List.of("offline_access", "uma_authorization", "default-roles-bank", Roles.AUDITOR)))));

        assertThat(caller.roles()).containsExactly(Roles.AUDITOR);
    }

    @Test
    @DisplayName("un jeton sans entite juridique est rejete, sans valeur par defaut")
    void a_token_without_legal_entity_is_rejected() {
        Map<String, Object> sansEntite = new java.util.HashMap<>(claims(Map.of()));
        sansEntite.remove("legal_entity");

        assertThatThrownBy(() -> factory.from(sansEntite))
            .isInstanceOf(KeycloakCallerFactory.MissingClaimException.class)
            .hasMessageContaining("legal_entity")
            .hasMessageContaining("aucune valeur par defaut");
    }

    @Test
    @DisplayName("une revendication d'entite mal formee est rejetee plutot qu'ignoree")
    void a_malformed_entity_claim_is_rejected() {
        assertThatThrownBy(() -> factory.from(claims(Map.of("legal_entity", "pas-un-uuid"))))
            .isInstanceOf(KeycloakCallerFactory.MissingClaimException.class)
            .hasMessageContaining("inexploitable");
    }

    @Test
    @DisplayName("un profil siege, sans agence, reste valide")
    void a_head_office_profile_has_no_branch() {
        Map<String, Object> sansAgence = new java.util.HashMap<>(claims(Map.of(
            "resource_access", Map.of(
                "core-banking", Map.of("roles", List.of(Roles.ACCOUNTANT))))));
        sansAgence.remove("branch");

        Caller caller = factory.from(sansAgence);
        assertThat(caller.branchId()).isNull();
    }

    @Test
    @DisplayName("un jeton sans role ne porte aucune habilitation, il n'est pas refuse pour autant")
    void a_token_without_roles_carries_no_permission() {
        Caller caller = factory.from(claims(Map.of()));

        assertThat(caller.roles()).isEmpty();
        // Le refus viendra de la politique, a l'operation demandee, avec un motif exploitable.
        var service = new AuthorizationService(AuthorizationAudit.none());
        assertThat(service.decide(caller, Operation.CASH_OPERATION,
            AccessTarget.inBranch(caller.legalEntityId(), caller.branchId())).allowed()).isFalse();
    }
}
