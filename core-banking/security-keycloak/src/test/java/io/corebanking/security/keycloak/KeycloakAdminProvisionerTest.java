package io.corebanking.security.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.security.RoleCatalogue;
import io.corebanking.security.RoleDefinition;
import io.corebanking.security.RoleStartupTask;
import io.corebanking.security.Roles;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeycloakAdminProvisionerTest {

    private static final String CLIENT = "core-banking";
    private static final String CLIENT_UUID = "7c1f0a4e-0000-0000-0000-000000000001";

    /** Royaume simule : reponses programmees, requetes enregistrees. */
    private static final class FakeKeycloak implements HttpExchange {
        private final List<Request> requests = new ArrayList<>();
        private final Deque<Response> scripted = new ArrayDeque<>();
        private int tokenIssues;

        void script(Response... responses) {
            scripted.addAll(List.of(responses));
        }

        @Override
        public Response send(Request request) {
            requests.add(request);
            if (request.uri().endsWith("/token")) {
                tokenIssues++;
                return new Response(200,
                    "{\"access_token\":\"jeton-" + tokenIssues + "\",\"expires_in\":300}");
            }
            if (!scripted.isEmpty()) {
                return scripted.poll();
            }
            if (request.uri().contains("/clients?clientId=")) {
                return new Response(200, "[{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT + "\"}]");
            }
            return new Response(200, "[]");
        }

        List<Request> to(String fragment) {
            return requests.stream().filter(r -> r.uri().contains(fragment)).toList();
        }
    }

    private final AtomicInteger sleeps = new AtomicInteger();

    private KeycloakAdminProvisioner provisioner(FakeKeycloak realm) {
        var config = new KeycloakAdminConfig("https://auth.bank.ci", "banque", "provisioning",
                                             () -> "secret-tres-confidentiel", 3,
                                             Duration.ofMillis(1));
        return new KeycloakAdminProvisioner(config, realm, duration -> sleeps.incrementAndGet());
    }

    @Test
    @DisplayName("le jeton est obtenu une fois et reutilise pour les appels suivants")
    void the_token_is_obtained_once_and_reused() {
        FakeKeycloak realm = new FakeKeycloak();
        var provisioner = provisioner(realm);

        provisioner.existingClientRoles(CLIENT);
        provisioner.existingClientRoles(CLIENT);
        provisioner.createRole(CLIENT, RoleCatalogue.require(Roles.TELLER));

        // Sans cache, chaque appel couterait une authentification, sur chaque instance, a chaque
        // demarrage.
        assertThat(realm.tokenIssues).isEqualTo(1);
        assertThat(realm.to("/clients?clientId=")).hasSize(1);   // resolution du client mise en cache
    }

    @Test
    @DisplayName("le role est cree avec sa description metier et ses attributs engendres")
    void a_role_is_created_with_its_description_and_generated_attributes() {
        FakeKeycloak realm = new FakeKeycloak();
        provisioner(realm).createRole(CLIENT, RoleCatalogue.require(Roles.TELLER));

        var creation = realm.to("/roles").stream()
            .filter(r -> r.method().equals("POST")).findFirst().orElseThrow();

        assertThat(creation.uri()).isEqualTo(
            "https://auth.bank.ci/admin/realms/banque/clients/" + CLIENT_UUID + "/roles");
        assertThat(creation.body())
            .contains("\"name\":\"teller\"")
            .contains("Operations de caisse")               // description du catalogue
            .contains("\"category\": [\"RESEAU\"]")         // attribut du catalogue
            .contains("CASH_OPERATION");                    // operations engendrees depuis la politique
        assertThat(creation.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    @DisplayName("la mise a jour vise le role par son nom, en PUT")
    void an_update_targets_the_role_by_name() {
        FakeKeycloak realm = new FakeKeycloak();
        provisioner(realm).updateRole(CLIENT, RoleCatalogue.require(Roles.BRANCH_MANAGER));

        var update = realm.to("/roles/").stream()
            .filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();

        assertThat(update.uri()).endsWith("/roles/branch_manager");
    }

    @Test
    @DisplayName("un conflit a la creation vaut succes : deux instances demarrent ensemble")
    void a_conflict_on_create_is_the_expected_outcome() {
        FakeKeycloak realm = new FakeKeycloak();
        realm.script(new HttpExchange.Response(200,
                "[{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT + "\"}]"),
            new HttpExchange.Response(409, "{\"errorMessage\":\"Role with name teller already exists\"}"));

        // Le role existe deja : c'est le resultat recherche, pas une erreur.
        provisioner(realm).createRole(CLIENT, RoleCatalogue.require(Roles.TELLER));
    }

    @Test
    @DisplayName("une defaillance passagere est reessayee, puis aboutit")
    void a_transient_failure_is_retried() {
        FakeKeycloak realm = new FakeKeycloak();
        realm.script(new HttpExchange.Response(200,
                "[{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT + "\"}]"),
            new HttpExchange.Response(503, "Service Unavailable"),
            new HttpExchange.Response(200, "[{\"name\":\"teller\"}]"));

        var roles = provisioner(realm).existingClientRoles(CLIENT);

        assertThat(roles).containsExactly("teller");
        assertThat(sleeps.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("un refus d'authentification n'est jamais reessaye")
    void an_auth_failure_is_never_retried() {
        FakeKeycloak realm = new FakeKeycloak();
        realm.script(new HttpExchange.Response(200,
                "[{\"id\":\"" + CLIENT_UUID + "\",\"clientId\":\"" + CLIENT + "\"}]"),
            new HttpExchange.Response(403, "Forbidden"));

        assertThatThrownBy(() -> provisioner(realm).existingClientRoles(CLIENT))
            .isInstanceOf(KeycloakAdminProvisioner.ProvisioningException.class);

        // Reessayer ne corrigerait pas un droit manquant, et pourrait verrouiller le compte.
        assertThat(sleeps.get()).isZero();
    }

    @Test
    @DisplayName("un fournisseur d'identite injoignable echoue apres le nombre de tentatives prevu")
    void an_unreachable_provider_fails_after_the_configured_attempts() {
        HttpExchange injoignable = request -> {
            if (request.uri().endsWith("/token")) {
                return new HttpExchange.Response(200, "{\"access_token\":\"j\",\"expires_in\":300}");
            }
            throw new HttpExchange.TransportException("connexion refusee", null);
        };
        var config = new KeycloakAdminConfig("https://auth.bank.ci", "banque", "provisioning",
                                             () -> "s", 3, Duration.ofMillis(1));
        var provisioner = new KeycloakAdminProvisioner(config, injoignable,
                                                       duration -> sleeps.incrementAndGet());

        assertThatThrownBy(() -> provisioner.existingClientRoles(CLIENT))
            .isInstanceOf(KeycloakAdminProvisioner.ProvisioningException.class)
            .hasMessageContaining("injoignable apres 3 tentative(s)");
        assertThat(sleeps.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("un client absent du royaume est signale explicitement")
    void a_missing_client_is_reported() {
        FakeKeycloak realm = new FakeKeycloak();
        realm.script(new HttpExchange.Response(200, "[]"));

        assertThatThrownBy(() -> provisioner(realm).existingClientRoles(CLIENT))
            .isInstanceOf(KeycloakAdminProvisioner.ProvisioningException.class)
            .hasMessageContaining("creer le client d'abord");
    }

    @Test
    @DisplayName("le secret du compte de service n'apparait ni dans la configuration ni dans les erreurs")
    void the_service_account_secret_never_leaks() {
        var config = new KeycloakAdminConfig("https://auth.bank.ci", "banque", "provisioning",
                                             () -> "secret-tres-confidentiel", 1,
                                             Duration.ofMillis(1));
        assertThat(config.toString()).doesNotContain("secret-tres-confidentiel").contains("***");

        HttpExchange refus = request -> new HttpExchange.Response(401, "invalid_client");
        var provisioner = new KeycloakAdminProvisioner(config, refus, duration -> { });

        assertThatThrownBy(() -> provisioner.existingClientRoles(CLIENT))
            .isInstanceOf(KeycloakAdminProvisioner.ProvisioningException.class)
            .hasMessageNotContaining("secret-tres-confidentiel");
    }

    @Test
    @DisplayName("l'API d'administration en clair est refusee a la construction")
    void a_plaintext_admin_endpoint_is_refused() {
        assertThatThrownBy(() -> KeycloakAdminConfig.of("http://auth.bank.ci", "banque", "p",
                                                        () -> "s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("de bout en bout : la tache de demarrage cree les huit roles du catalogue")
    void end_to_end_the_startup_task_creates_the_catalogue() {
        FakeKeycloak realm = new FakeKeycloak();
        var report = new RoleStartupTask(provisioner(realm)).run();

        assertThat(report.created()).containsExactlyInAnyOrderElementsOf(RoleCatalogue.declared());
        assertThat(realm.to("/roles").stream().filter(r -> r.method().equals("POST")).toList())
            .hasSize(RoleCatalogue.declared().size());

        // Chaque creation porte bien la charge utile attendue.
        for (RoleDefinition role : RoleCatalogue.definitions().values()) {
            assertThat(realm.to("/roles").stream()
                .filter(r -> r.method().equals("POST"))
                .anyMatch(r -> r.body().contains("\"name\":\"" + role.code() + "\"")))
                .as("creation du role %s", role.code())
                .isTrue();
        }
    }
}
