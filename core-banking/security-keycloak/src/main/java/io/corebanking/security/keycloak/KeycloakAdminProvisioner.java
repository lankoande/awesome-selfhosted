package io.corebanking.security.keycloak;

import io.corebanking.security.KeycloakProvisioning;
import io.corebanking.security.RoleDefinition;
import io.corebanking.security.RoleProvisioner;
import io.corebanking.security.json.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisionnement des roles par l'API d'administration Keycloak.
 *
 * <p>L'adaptateur ne decide rien sur les roles : ce qui doit exister vient du catalogue, et
 * {@code RoleStartupTask} orchestre. Il traduit, et il traite ce que le reseau impose.
 *
 * <h2>Ce que le reseau impose, et qui n'est pas cosmetique</h2>
 *
 * <ul>
 *   <li><b>Un jeton mis en cache, renouvele avant son terme.</b> Sans cache, provisionner huit
 *       roles demanderait une dizaine d'authentifications par demarrage, sur chaque instance.</li>
 *   <li><b>Reprise sur defaillance passagere uniquement.</b> Dans un orchestrateur, l'application
 *       demarre souvent avant que le fournisseur d'identite ne reponde ; quelques tentatives
 *       espacees evitent un echec de demarrage sans cause reelle. En revanche un {@code 401} ou un
 *       {@code 403} n'est jamais reessaye : reessayer ne corrigera pas un secret errone, et la
 *       repetition peut verrouiller le compte de service — une erreur de configuration deviendrait
 *       une indisponibilite.</li>
 *   <li><b>Un {@code 409} a la creation vaut succes.</b> Deux instances qui demarrent ensemble
 *       tentent la meme creation ; la seconde recoit un conflit. C'est le resultat recherche, pas
 *       une erreur.</li>
 * </ul>
 */
public final class KeycloakAdminProvisioner implements RoleProvisioner {

    /** Marge de renouvellement : le jeton est renouvele avant son expiration reelle. */
    private static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

    private final KeycloakAdminConfig config;
    private final HttpExchange exchange;
    private final Sleeper sleeper;

    private String accessToken;
    private Instant tokenExpiry = Instant.MIN;
    private String cachedClientId;
    private String cachedClientUuid;

    /** Attente entre deux tentatives. Neutralisee dans les tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProvisioningException("Provisionnement interrompu", null);
                }
            };
        }
    }

    public KeycloakAdminProvisioner(KeycloakAdminConfig config, HttpExchange exchange,
                                    Sleeper sleeper) {
        this.config = config;
        this.exchange = exchange;
        this.sleeper = sleeper;
    }

    public KeycloakAdminProvisioner(KeycloakAdminConfig config) {
        this(config, new JdkHttpExchange(), Sleeper.real());
    }

    // ------------------------------------------------------------------ RoleProvisioner

    @Override
    public Set<String> existingClientRoles(String clientId) {
        String uuid = clientUuid(clientId);
        HttpExchange.Response response = callWithRetry(HttpExchange.Request.get(
            config.adminBase() + "/clients/" + uuid + "/roles?briefRepresentation=true",
            bearerHeaders()));

        if (!response.isSuccess()) {
            throw failure("lecture des roles du client " + clientId, response);
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object entry : Json.parseArray(response.body())) {
            if (entry instanceof Map<?, ?> role && role.get("name") instanceof String name) {
                roles.add(name);
            }
        }
        return roles;
    }

    @Override
    public void createRole(String clientId, RoleDefinition role) {
        String uuid = clientUuid(clientId);
        HttpExchange.Response response = callWithRetry(HttpExchange.Request.post(
            config.adminBase() + "/clients/" + uuid + "/roles", jsonHeaders(), rolePayload(role)));

        // Deux instances qui demarrent ensemble tentent la meme creation : le conflit est le
        // resultat recherche.
        if (response.status() == 409) {
            return;
        }
        if (!response.isSuccess()) {
            throw failure("creation du role " + role.code(), response);
        }
    }

    @Override
    public void updateRole(String clientId, RoleDefinition role) {
        String uuid = clientUuid(clientId);
        HttpExchange.Response response = callWithRetry(HttpExchange.Request.put(
            config.adminBase() + "/clients/" + uuid + "/roles/" + encode(role.code()),
            jsonHeaders(), rolePayload(role)));

        if (!response.isSuccess()) {
            throw failure("mise a jour du role " + role.code(), response);
        }
    }

    // ------------------------------------------------------------------ interne

    /**
     * Identifiant interne du client, resolu depuis son {@code clientId} lisible.
     *
     * <p>Toutes les routes d'administration des roles emploient l'identifiant interne, jamais le
     * {@code clientId}. La resolution est mise en cache : elle ne change pas pendant la vie du
     * processus.
     */
    private String clientUuid(String clientId) {
        if (clientId.equals(cachedClientId) && cachedClientUuid != null) {
            return cachedClientUuid;
        }
        HttpExchange.Response response = callWithRetry(HttpExchange.Request.get(
            config.adminBase() + "/clients?clientId=" + encode(clientId), bearerHeaders()));

        if (!response.isSuccess()) {
            throw failure("resolution du client " + clientId, response);
        }
        List<Object> clients = Json.parseArray(response.body());
        if (clients.isEmpty()) {
            throw new ProvisioningException(
                "Client « " + clientId + " » introuvable dans le royaume " + config.realm()
                + ". Les roles ne peuvent pas etre provisionnes : creer le client d'abord.", null);
        }
        if (!(clients.get(0) instanceof Map<?, ?> client)
            || !(client.get("id") instanceof String uuid)) {
            throw new ProvisioningException(
                "Reponse inattendue a la resolution du client " + clientId, null);
        }
        cachedClientId = clientId;
        cachedClientUuid = uuid;
        return uuid;
    }

    private String token() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        String form = "grant_type=client_credentials"
            + "&client_id=" + encode(config.serviceClientId())
            + "&client_secret=" + encode(config.serviceClientSecret().get());

        HttpExchange.Response response = callWithRetry(HttpExchange.Request.post(
            config.tokenEndpoint(),
            Map.of("Content-Type", "application/x-www-form-urlencoded"), form));

        if (!response.isSuccess()) {
            // Le corps de la reponse est restitue, jamais celui de la requete : il porte le secret.
            throw new ProvisioningException(
                "Authentification du compte de service refusee (" + response.status()
                + ") sur " + config.realm(), null);
        }
        Map<String, Object> payload = Json.parseObject(response.body());
        if (!(payload.get("access_token") instanceof String issued)) {
            throw new ProvisioningException("Jeton absent de la reponse d'authentification", null);
        }
        long expiresIn = payload.get("expires_in") instanceof java.math.BigDecimal seconds
            ? seconds.longValue() : 60L;

        accessToken = issued;
        tokenExpiry = Instant.now().plusSeconds(expiresIn).minus(RENEWAL_MARGIN);
        return accessToken;
    }

    private Map<String, String> bearerHeaders() {
        return Map.of("Authorization", "Bearer " + token(), "Accept", "application/json");
    }

    private Map<String, String> jsonHeaders() {
        return Map.of("Authorization", "Bearer " + token(),
                      "Content-Type", "application/json",
                      "Accept", "application/json");
    }

    static String rolePayload(RoleDefinition role) {
        return "{"
            + "\"name\":" + Json.quote(role.code()) + ","
            + "\"description\":" + Json.quote(KeycloakProvisioning.describe(role)) + ","
            + "\"attributes\":" + KeycloakProvisioning.attributesJson(role)
            + "}";
    }

    private HttpExchange.Response callWithRetry(HttpExchange.Request request) {
        HttpExchange.Response last = null;
        RuntimeException transportFailure = null;

        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                last = exchange.send(request);
                transportFailure = null;
                if (last.isSuccess() || last.isAuthFailure() || !last.isRetryable()) {
                    return last;
                }
            } catch (HttpExchange.TransportException e) {
                transportFailure = e;
            }
            if (attempt < config.maxAttempts()) {
                sleeper.sleep(config.retryBackoff());
            }
        }
        if (transportFailure != null) {
            throw new ProvisioningException(
                "Fournisseur d'identite injoignable apres " + config.maxAttempts()
                + " tentative(s) : " + request.method() + " " + request.uri(), transportFailure);
        }
        return last;
    }

    private ProvisioningException failure(String action, HttpExchange.Response response) {
        return new ProvisioningException(
            "Echec du provisionnement — " + action + " : statut " + response.status()
            + (response.body() == null || response.body().isBlank()
               ? "" : ", reponse " + response.body()), null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Echec de provisionnement.
     *
     * <p>Volontairement non rattrapable par la tache de demarrage : une application qui sert alors
     * que ses roles n'existent pas refuse toutes les operations tout en paraissant saine. Mieux vaut
     * un demarrage qui echoue avec un motif qu'une instance verte et inutilisable.
     */
    public static class ProvisioningException extends RuntimeException {
        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
