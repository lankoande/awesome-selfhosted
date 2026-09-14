package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoleProvisioningTest {

    private static final String CLIENT = "core-banking";

    @Test
    @DisplayName("le catalogue de roles est celui de la politique, pas une liste tenue a part")
    void the_catalogue_is_derived_from_the_policy() {
        Set<String> catalogue = RoleCatalogue.declared();

        assertThat(catalogue).isNotEmpty();
        // Chaque role du catalogue ouvre au moins une operation : aucun role decoratif.
        for (String role : catalogue) {
            assertThat(RoleCatalogue.operationsOf(role))
                .as("role %s", role)
                .isNotEmpty();
        }
        assertThat(RoleCatalogue.grantingNothing()).isEmpty();
    }

    @Test
    @DisplayName("aucune constante de Roles n'est orpheline, aucun role de la politique n'est inconnu")
    void constants_and_policy_agree() throws IllegalAccessException {
        Set<String> constants = new TreeSet<>();
        for (Field field : Roles.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }

        // Une constante jamais citee par une regle est un role mort : il sera provisionne,
        // attribue, et n'ouvrira rien.
        assertThat(constants)
            .as("constantes de Roles non citees dans SecurityConfig")
            .isEqualTo(RoleCatalogue.declared());
    }

    @Test
    @DisplayName("tout poste ne reference que des roles connus de la politique")
    void job_profiles_reference_known_roles_only() {
        assertThat(KeycloakProvisioning.profilesReferencingUnknownRoles()).isEmpty();
    }

    @Test
    @DisplayName("tout role du catalogue est porte par au moins un poste, sinon il est inattribuable")
    void every_role_is_carried_by_a_profile() {
        Set<String> portes = new TreeSet<>();
        JobProfile.all().values().forEach(portes::addAll);

        assertThat(RoleCatalogue.declared())
            .as("roles qu'aucun poste ne porte : personne ne pourra jamais les detenir")
            .isSubsetOf(portes);
    }

    @Test
    @DisplayName("l'import partiel contient tous les roles et tous les groupes, sous le bon client")
    void the_partial_import_covers_roles_and_groups() {
        String json = KeycloakProvisioning.partialImport(CLIENT);

        assertThat(json).contains("\"client\"").contains('"' + CLIENT + '"');
        for (String role : RoleCatalogue.declared()) {
            assertThat(json).as("role %s", role).contains('"' + role + '"');
        }
        for (JobProfile profile : JobProfile.values()) {
            assertThat(json).as("poste %s", profile).contains('"' + profile.groupName() + '"');
        }
        // La description est engendree : elle enumere les operations reellement ouvertes.
        assertThat(KeycloakProvisioning.describe(Roles.TELLER)).contains("CASH_OPERATION");
    }

    @Test
    @DisplayName("un role attendu et absent du royaume est signale comme silencieusement bloquant")
    void a_missing_role_is_reported_as_silently_blocking() {
        Set<String> observed = new TreeSet<>(RoleCatalogue.declared());
        observed.remove(Roles.ACCOUNTANT);

        var drift = KeycloakProvisioning.drift(observed);

        assertThat(drift.isClean()).isFalse();
        assertThat(drift.missing()).containsExactly(Roles.ACCOUNTANT);
        assertThat(drift.report()).contains("inaccessibles a tous");
    }

    @Test
    @DisplayName("un role present dans le royaume et cite par aucune regle est signale aussi")
    void an_orphan_role_in_the_realm_is_reported() {
        Set<String> observed = new TreeSet<>(RoleCatalogue.declared());
        observed.add("super_admin");

        var drift = KeycloakProvisioning.drift(observed);

        assertThat(drift.unexpected()).containsExactly("super_admin");
        assertThat(drift.report()).contains("font croire a un droit qui n'existe pas");
    }

    @Test
    @DisplayName("le provisionnement engendre est exactement conforme a la politique")
    void the_generated_provisioning_has_no_drift() {
        assertThat(KeycloakProvisioning.drift(RoleCatalogue.declared()).isClean()).isTrue();
    }

    // ------------------------------------------------------------------ segregation des taches

    @Test
    @DisplayName("un auditeur qui detient un role operationnel voit son jeton refuse en bloc")
    void an_auditor_holding_an_operational_role_is_refused() {
        var factory = new KeycloakCallerFactory(CLIENT);

        assertThatThrownBy(() -> factory.from(Map.of(
            "sub", "x1",
            "preferred_username", "a.diallo",
            "legal_entity", UUID.randomUUID().toString(),
            "resource_access", Map.of(CLIENT, Map.of("roles",
                List.of(Roles.AUDITOR, Roles.TELLER))))))
            .isInstanceOf(KeycloakCallerFactory.SegregationOfDutiesException.class)
            .hasMessageContaining("Cumul de roles interdit")
            .hasMessageContaining("ne peut pas operer sur le perimetre qu'il controle");
    }

    @Test
    @DisplayName("le cumul guichetier et chef d'agence reste permis : le controle agit par operation")
    void teller_and_branch_manager_remain_compatible() {
        // La regle du valideur distinct de l'auteur empeche deja de se controler soi-meme, sans
        // empecher de travailler. Interdire ce cumul rendrait la plupart des agences inexploitables.
        assertThat(SecurityConfig.segregationConflict(
            Set.of(Roles.TELLER, Roles.BRANCH_MANAGER))).isEmpty();
        assertThat(JobProfile.CHEF_AGENCE.roles())
            .containsExactlyInAnyOrder(Roles.TELLER, Roles.BRANCH_MANAGER);
    }

    @Test
    @DisplayName("aucun poste ne porte lui-meme un cumul interdit")
    void no_job_profile_carries_a_forbidden_combination() {
        for (JobProfile profile : JobProfile.values()) {
            assertThat(SecurityConfig.segregationConflict(profile.roles()))
                .as("poste %s", profile)
                .isEmpty();
        }
    }
}
