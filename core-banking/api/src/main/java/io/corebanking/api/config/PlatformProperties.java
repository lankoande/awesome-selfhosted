package io.corebanking.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reglages du socle : base de donnees, montee de version, client Keycloak. */
@ConfigurationProperties(prefix = "corebanking")
public record PlatformProperties(Datasource datasource, Schema schema, Security security,
                                 Keycloak keycloak) {

    public record Datasource(String url, String username, String password, int poolSize) {}

    public record Schema(boolean migrateOnStartup) {}

    public record Security(String clientId) {}

    /**
     * Compte de service de l'API d'administration Keycloak, pour declarer au demarrage les roles
     * de {@code roles.json} qui manquent au royaume. Sans adresse, rien n'est provisionne et le
     * demarrage le dit.
     */
    public record Keycloak(String adminUrl, String realm, String serviceClientId,
                           String serviceClientSecret) {
        public boolean configured() {
            return adminUrl != null && !adminUrl.isBlank();
        }
    }
}
