package io.corebanking.loan;

/**
 * Portee du declassement par contagion.
 *
 * <p>Un client qui ne rembourse plus l'un de ses credits ne presente pas un risque different sur
 * les autres. La plupart des profils reglementaires imposent donc de declasser l'ensemble de ses
 * encours au niveau le plus defavorable — c'est la contagion. L'ignorer sous-estime le risque
 * exactement la ou il se materialise.
 */
public enum Contagion {

    /** Chaque credit est classe pour lui-meme. */
    NONE,

    /** Tous les encours d'un meme client suivent le plus degrade d'entre eux. */
    CUSTOMER
}
