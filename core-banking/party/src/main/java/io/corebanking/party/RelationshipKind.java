package io.corebanking.party;

/** Nature d'une relation entre deux tiers. */
public enum RelationshipKind {
    /** Represente legalement la personne morale : gerant, president. */
    LEGAL_REPRESENTATIVE,
    /** Dispose d'un pouvoir sur les comptes du tiers. */
    MANDATE,
    /** Conjoint. */
    SPOUSE,
    /** Societe mere du tiers : la relation ne forme jamais de cycle. */
    PARENT_COMPANY,
    /** Membre du meme groupe, sans lien de detention declare. */
    GROUP_MEMBER;

    /** Vrai si la relation decrit une detention : elle se lit en chaine, et ne boucle pas. */
    public boolean hierarchical() {
        return this == PARENT_COMPANY;
    }
}
