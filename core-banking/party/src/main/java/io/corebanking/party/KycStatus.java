package io.corebanking.party;

/**
 * Etat de la connaissance client.
 *
 * <ul>
 *   <li>{@code PENDING} : dossier ouvert, pas encore verifie. Aucun compte ne s'ouvre dessus.</li>
 *   <li>{@code VERIFIED} : verifie, avec une date de revue.</li>
 *   <li>{@code EXPIRED} : la revue est depassee. Les comptes existants fonctionnent, aucun
 *       nouveau compte ni credit ne s'ouvre — la restriction est progressive, jamais un blocage
 *       brutal non annonce.</li>
 *   <li>{@code BLOCKED} : filtrage ou decision de conformite. Plus aucune operation.</li>
 * </ul>
 */
public enum KycStatus {
    PENDING, VERIFIED, EXPIRED, BLOCKED;

    /** Vrai si un nouveau compte ou un nouveau credit peut etre ouvert sur ce dossier. */
    public boolean allowsOnboarding() {
        return this == VERIFIED;
    }

    /** Vrai si les comptes existants peuvent continuer a fonctionner. */
    public boolean allowsOperations() {
        return this != BLOCKED;
    }
}
