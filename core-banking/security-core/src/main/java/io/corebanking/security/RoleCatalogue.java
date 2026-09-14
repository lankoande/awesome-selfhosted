package io.corebanking.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalogue des roles, <b>derive de la politique</b> et non saisi separement.
 *
 * <h2>Pourquoi les roles ne se creent pas dans la console Keycloak</h2>
 *
 * <p>Un role n'a pas d'existence propre : il n'est qu'un nom qui apparait dans une regle de
 * {@link SecurityConfig}. D'ou deux situations, symetriques et toutes deux nuisibles, qu'une saisie
 * manuelle rend inevitables :
 *
 * <ul>
 *   <li><b>Un role cree dans Keycloak et absent de la politique n'ouvre rien.</b> Il est attribue,
 *       il figure dans les jetons, il rassure — et il ne donne aucun droit. Le defaut se manifeste
 *       par un refus incomprehensible : l'agent « a le role » et n'accede pas.</li>
 *   <li><b>Un role present dans la politique et absent de Keycloak ne peut etre detenu par
 *       personne.</b> L'operation correspondante est inaccessible a tous, sans qu'aucune erreur ne
 *       le signale : la politique est coherente, le refus est regulier, et l'operation est
 *       simplement morte.</li>
 * </ul>
 *
 * <p>Le catalogue est donc calcule : c'est l'union des roles cites par les regles. Keycloak en est
 * le <b>reflet provisionne</b>, pas la source. Un ecart entre les deux est un defaut de
 * deploiement, detectable ({@link KeycloakProvisioning#drift}) et non une divergence a arbitrer.
 */
public final class RoleCatalogue {

    private RoleCatalogue() {}

    /** Roles cites par au moins une regle : la seule definition qui fasse autorite. */
    public static Set<String> declared() {
        Set<String> roles = new TreeSet<>();
        SecurityConfig.policy().values().forEach(rule -> roles.addAll(rule.roles()));
        return roles;
    }

    /**
     * Roles cites par une regle mais qui n'ouvrent aucune operation. Toujours vide par
     * construction — la methode existe pour le test qui le verifie.
     */
    public static Set<String> grantingNothing() {
        Set<String> orphans = new LinkedHashSet<>(declared());
        SecurityConfig.policy().values().forEach(rule -> orphans.removeAll(rule.roles()));
        return orphans;
    }

    /** Operations qu'un role permet de realiser, pour la revue d'habilitations. */
    public static Set<Operation> operationsOf(String role) {
        Set<Operation> operations = new LinkedHashSet<>();
        SecurityConfig.policy().forEach((operation, rule) -> {
            if (rule.roles().contains(role)) {
                operations.add(operation);
            }
        });
        return operations;
    }
}
