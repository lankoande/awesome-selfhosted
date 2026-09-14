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
 * <h2>Roles de client, et non roles de royaume</h2>
 *
 * <p>Seuls les roles du <b>client backend</b> sont retenus : {@code resource_access.<client>.roles}.
 * Les roles de royaume ({@code realm_access.roles}) sont ignores, sauf ceux explicitement declares
 * comme transverses. Quatre raisons :
 *
 * <ul>
 *   <li><b>Cloisonnement.</b> Un role de royaume est visible de toutes les applications du royaume.
 *       Un role {@code teller} defini au niveau du royaume a du sens pour le core banking et
 *       aucun pour l'intranet — mais il arrive dans les jetons des deux, et il suffit qu'une autre
 *       application decide de l'honorer pour qu'une habilitation bancaire fuite hors du
 *       perimetre.</li>
 *   <li><b>Espace de noms.</b> Les roles de royaume partagent un espace plat. Un {@code admin}
 *       cree pour un autre applicatif entre en collision avec celui du core banking. Les roles de
 *       client sont nommes dans le client.</li>
 *   <li><b>Gouvernance.</b> Les roles du client suivent le cycle de vie de l'application : ajouter
 *       une operation et son role reste confine a un seul client, revu avec le code.</li>
 *   <li><b>Coherence avec l'audience.</b> Un jeton porte les {@code resource_access} des clients de
 *       son audience. Un role de royaume, lui, arrive dans un jeton emis pour n'importe quel
 *       client — y compris un client qui n'a rien a voir avec la banque.</li>
 * </ul>
 *
 * <p>Les roles reellement transverses — un auditeur qui audite plusieurs systemes — existent. Ils
 * sont alors declares un par un a la construction, et cette liste est courte et revue. Le defaut
 * reste l'absence de droit.
 *
 * <h2>Ce qui n'est pas lu, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>Les plafonds.</b> Ils vivent dans {@link SecurityConfig}. Un attribut Keycloak mal
 *       renseigne ne doit pas pouvoir elever le plafond d'un guichetier sans revue de code.</li>
 *   <li><b>Les roles des autres clients.</b> {@code resource_access} peut contenir les roles que le
 *       porteur detient sur d'autres applications ; les accepter reviendrait a laisser une
 *       habilitation accordee ailleurs ouvrir un droit ici.</li>
 *   <li><b>Les roles techniques de Keycloak</b> ({@code offline_access}, {@code uma_authorization},
 *       {@code default-roles-*}) : ecartes, ils n'ont pas de sens metier.</li>
 * </ul>
 *
 * <h2>Consequences sur la configuration Keycloak</h2>
 *
 * <p>Pour que {@code resource_access.<client>.roles} figure dans le jeton alors que celui-ci est
 * emis a un client frontal, trois points de configuration sont necessaires et souvent oublies :
 *
 * <ol>
 *   <li>un <b>mapper d'audience</b> sur le client frontal, ajoutant le client backend a
 *       {@code aud} ;</li>
 *   <li>les roles du client backend <b>assignes a l'utilisateur</b>, directement ou par groupe ;</li>
 *   <li>le <b>Full scope allowed</b> desactive sur le client frontal, et un scope dedie portant les
 *       roles du backend — sans quoi le jeton embarque l'integralite des roles du porteur, sur tous
 *       les clients, ce que la regle ci-dessus rend inutile mais qui grossit le jeton et fuite la
 *       cartographie des habilitations.</li>
 * </ol>
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
    private final Set<String> trustedRealmRoles;

    /**
     * @param clientId client Keycloak dont les roles sont retenus. Obligatoire : sans lui, aucun
     *                 role ne serait accepte et toute operation serait refusee.
     */
    public KeycloakCallerFactory(String clientId) {
        this(clientId, Set.of());
    }

    /**
     * @param trustedRealmRoles roles de royaume acceptes par exception, pour les identites
     *                          reellement transverses a plusieurs applications. Tout role de
     *                          royaume absent de cette liste est ignore.
     */
    public KeycloakCallerFactory(String clientId, Set<String> trustedRealmRoles) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException(
                "Identifiant du client backend obligatoire : les habilitations sont portees par "
                + "les roles de ce client, pas par ceux du royaume.");
        }
        this.clientId = clientId;
        this.trustedRealmRoles = Set.copyOf(Objects.requireNonNull(trustedRealmRoles,
                                                                   "trustedRealmRoles"));
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

        // Les roles du client backend : la source normale des habilitations.
        if (claims.get(CLAIM_RESOURCE) instanceof Map<?, ?> resourceAccess) {
            roles.addAll(rolesOf(resourceAccess.get(clientId)));
        }

        // Les roles de royaume ne sont retenus que s'ils ont ete declares transverses.
        rolesOf(claims.get(CLAIM_REALM)).stream()
            .filter(trustedRealmRoles::contains)
            .forEach(roles::add);

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
