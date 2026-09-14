package io.corebanking.schema;

import java.util.UUID;

/**
 * Traduit une reference de schema en compte imputable.
 *
 * <p>Le resolveur est construit pour un contexte d'execution donne — une entite, un contrat, une
 * agence — et c'est lui qui connait le plan comptable applicable. Le schema reste ainsi neutre
 * vis-a-vis du referentiel comptable local.
 */
@FunctionalInterface
public interface AccountResolver {

    UUID resolve(AccountRef reference);

    /** Reference non resolvable dans le contexte courant. */
    class UnresolvableAccountException extends RuntimeException {
        public UnresolvableAccountException(AccountRef reference, String detail) {
            super("Compte « " + reference + " » non resolvable : " + detail);
        }
    }
}
