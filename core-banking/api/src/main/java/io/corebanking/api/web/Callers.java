package io.corebanking.api.web;

import io.corebanking.security.Caller;
import java.util.UUID;

/** Ce que l'API derive de l'appelant, et jamais du corps de la requete. */
public final class Callers {

    private Callers() {}

    /** L'auteur d'une operation est le sujet du jeton — un identifiant Keycloak, donc un UUID. */
    public static UUID actorId(Caller caller) {
        try {
            return UUID.fromString(caller.subjectId());
        } catch (IllegalArgumentException e) {
            throw new io.corebanking.security.KeycloakCallerFactory.MissingClaimException(
                "sub", caller.subjectId());
        }
    }

    /** L'agence de l'appelant, exigee pour ce qui s'ouvre ou se sert dans une agence. */
    public static UUID branchId(Caller caller) {
        if (caller.branchId() == null) {
            throw new io.corebanking.security.KeycloakCallerFactory.MissingClaimException("branch");
        }
        return caller.branchId();
    }
}
