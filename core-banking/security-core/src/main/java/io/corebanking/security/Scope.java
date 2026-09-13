package io.corebanking.security;

/** Perimetre geographique et juridique dans lequel une operation est permise. */
public enum Scope {
    /** Uniquement les objets rattaches a l'agence de l'appelant. */
    OWN_BRANCH,
    /** Tout objet de l'entite juridique de l'appelant. */
    OWN_ENTITY,
    /**
     * Toute entite du groupe. Reserve a un tres petit nombre d'operations — consolidation, audit
     * groupe — et declare explicitement, jamais obtenu par defaut.
     */
    ANY_ENTITY
}
