package io.corebanking.security.keycloak;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Coordonnees du royaume et du compte de service d'administration.
 *
 * @param serviceClientSecret fourni par un {@link Supplier} et non par une chaine, de facon que le
 *                            secret vienne d'un coffre et puisse tourner sans redemarrage. Il n'est
 *                            jamais conserve en champ, jamais journalise, et {@link #toString()} le
 *                            masque — un secret d'administration dans une trace d'erreur se
 *                            retrouve ensuite dans un outil de supervision, indexe et conserve.
 * @param maxAttempts         tentatives en cas de defaillance passagere. Utile au demarrage : dans
 *                            un orchestrateur, l'application peut etre prete avant le fournisseur
 *                            d'identite.
 */
public record KeycloakAdminConfig(
    String baseUrl,
    String realm,
    String serviceClientId,
    Supplier<String> serviceClientSecret,
    int maxAttempts,
    Duration retryBackoff) {

    public KeycloakAdminConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(serviceClientId, "serviceClientId");
        Objects.requireNonNull(serviceClientSecret, "serviceClientSecret");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://localhost")) {
            throw new IllegalArgumentException(
                "L'API d'administration Keycloak doit etre jointe en HTTPS : « " + baseUrl
                + " ». Le secret du compte de service et les habilitations transitent par ce canal.");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts doit valoir au moins 1");
        }
    }

    public static KeycloakAdminConfig of(String baseUrl, String realm, String serviceClientId,
                                         Supplier<String> secret) {
        return new KeycloakAdminConfig(baseUrl, realm, serviceClientId, secret, 5,
                                       Duration.ofSeconds(2));
    }

    public String tokenEndpoint() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String adminBase() {
        return baseUrl + "/admin/realms/" + realm;
    }

    @Override
    public String toString() {
        return "KeycloakAdminConfig[baseUrl=" + baseUrl + ", realm=" + realm
               + ", serviceClientId=" + serviceClientId + ", serviceClientSecret=***]";
    }
}
