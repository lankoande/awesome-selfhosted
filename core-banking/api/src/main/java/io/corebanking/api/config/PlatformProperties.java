package io.corebanking.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Reglages du socle : base de donnees, montee de version, client Keycloak. */
@ConfigurationProperties(prefix = "corebanking")
public record PlatformProperties(Datasource datasource, Schema schema, Security security,
                                 Keycloak keycloak, MakerChecker makerChecker) {

    /** Delai au-dela duquel une operation en attente de double validation n'est plus decidable. */
    public record MakerChecker(int expiryHours) {
        public int expiryHoursOrDefault() {
            return expiryHours <= 0 ? 48 : expiryHours;
        }
    }

    public record Datasource(String url, String username, String password, int poolSize) {}

    /**
     * Montee de version au demarrage. Avec un compte proprietaire distinct, les migrations
     * s'executent sous lui et l'application ne possede rien : c'est ce qui rend la Row Level
     * Security effective pour elle. Sans compte distinct, un seul role fait tout — installation
     * de developpement, jamais de production.
     */
    public record Schema(boolean migrateOnStartup, String username, String password) {
        public boolean ownerConfigured() {
            return username != null && !username.isBlank();
        }
    }

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
