package io.corebanking.security;

/**
 * Reception des decisions d'habilitation.
 *
 * <p>Toute decision passe ici : les refus sans exception, et les acces reussis sur les operations
 * marquees comme tracees en lecture. L'implementation de production ecrit dans la table d'audit
 * immuable ; les tests en fournissent une en memoire.
 */
@FunctionalInterface
public interface AuthorizationAudit {

    void record(Caller caller, Operation operation, AccessTarget target, AccessDecision decision);

    /** Puits sans effet, pour les contextes ou la tracabilite est assuree en amont. */
    static AuthorizationAudit none() {
        return (caller, operation, target, decision) -> { };
    }
}
