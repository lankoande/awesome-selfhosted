package io.corebanking.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Construction d'un {@link Caller} a partir des revendications d'un jeton Keycloak.
 *
 * <p>La validation cryptographique du jeton — signature, emetteur, audience, expiration — releve du
 * serveur de ressources OAuth2 et a deja eu lieu quand cette classe est appelee. Ce qui est fait ici
 * est different et tout aussi necessaire : etablir que le jeton, meme valide, porte les
 * revendications indispensables, et n'accorder aucune interpretation genereuse a celles qui
 * manquent.
 *
 * <h2>Ce qui n'est pas lu, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>Les plafonds.</b> Ils vivent dans {@link SecurityConfig}. Un attribut Keycloak mal
 *       renseigne ne doit pas pouvoir elever le plafond d'un guichetier sans revue de code.</li>
 *   <li><b>Les roles de clients tiers.</b> Seuls les roles de royaume et ceux du client configure
 *       sont retenus. {@code resource_access} peut contenir les roles que le porteur detient sur
 *       d'autres applications ; les accepter reviendrait a laisser une habilitation accordee
 *       ailleurs ouvrir un droit ici.</li>
 *   <li><b>Les roles techniques de Keycloak</b> ({@code offline_access}, {@code uma_authorization},
 *       {@code default-roles-*}) : ecartes, ils n'ont pas de sens metier.</li>
 * </ul>
 */
public final class KeycloakCallerFactory {

    private static final String CLAIM_SUBJECT   = "sub";
    private static final String CLAIM_USERNAME  = "preferred_username";
    private static final String CLAIM_REALM     = "realm_access";
    private static final String CLAIM_RESOURCE  = "resource_access";
    private static final String CLAIM_ROLES     = "roles";
    private static final String CLAIM_ENTITY    = "legal_entity";
    private static final String CLAIM_BRANCH    = "branch";

    private static final Set<String> TECHNICAL_ROLES = Set.of("offline_access", "uma_authorization");

    private final String clientId;

    /** @param clientId client Keycloak dont les roles sont retenus ; {@code null} pour n'en retenir aucun */
    public KeycloakCallerFactory(String clientId) {
        this.clientId = clientId;
    }

    public Caller from(Map<String, Object> claims) {
        Objects.requireNonNull(claims, "claims");

        String subject = requireText(claims, CLAIM_SUBJECT);
        String username = optionalText(claims, CLAIM_USERNAME, subject);
        UUID legalEntityId = requireUuid(claims, CLAIM_ENTITY);
        UUID branchId = optionalUuid(claims, CLAIM_BRANCH);

        return new Caller(subject, username, extractRoles(claims), legalEntityId, branchId);
    }

    private Set<String> extractRoles(Map<String, Object> claims) {
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(rolesOf(claims.get(CLAIM_REALM)));

        if (clientId != null && claims.get(CLAIM_RESOURCE) instanceof Map<?, ?> resourceAccess) {
            roles.addAll(rolesOf(resourceAccess.get(clientId)));
        }

        roles.removeIf(role -> TECHNICAL_ROLES.contains(role) || role.startsWith("default-roles-"));
        return roles;
    }

    private static List<String> rolesOf(Object container) {
        if (container instanceof Map<?, ?> map && map.get(CLAIM_ROLES) instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static String requireText(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new MissingClaimException(name);
        }
        return text;
    }

    private static String optionalText(Map<String, Object> claims, String name, String fallback) {
        Object value = claims.get(name);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static UUID requireUuid(Map<String, Object> claims, String name) {
        String raw = requireText(claims, name);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new MissingClaimException(name, raw);
        }
    }

    private static UUID optionalUuid(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new MissingClaimException(name, text);
        }
    }

    /**
     * Revendication absente ou inexploitable.
     *
     * <p>Le jeton est alors rejete. Il n'existe pas de valeur par defaut : un jeton sans entite
     * juridique interprete comme un acces global est precisement la faille que ce refus previent.
     */
    public static class MissingClaimException extends RuntimeException {
        public MissingClaimException(String claim) {
            super("Revendication « " + claim + " » absente du jeton. "
                  + "Le jeton est rejete : aucune valeur par defaut n'est appliquee.");
        }

        public MissingClaimException(String claim, String value) {
            super("Revendication « " + claim + " » inexploitable : « " + value + " ».");
        }
    }
}
