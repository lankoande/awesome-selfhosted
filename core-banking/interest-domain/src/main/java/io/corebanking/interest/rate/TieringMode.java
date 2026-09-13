package io.corebanking.interest.rate;

/**
 * Mode d'application d'un bareme par tranches.
 *
 * <p>La difference est financierement majeure et n'est pas un detail d'implementation : sur un
 * solde de 6 000 000 avec 2 % jusqu'a 5 000 000 puis 3 % au-dela, le mode progressif rend
 * 100 000 + 30 000 = 130 000, le mode global 180 000. Le mode est une clause du contrat.
 */
public enum TieringMode {
    /** Chaque tranche est remuneree a son propre taux. */
    PROGRESSIVE,
    /** La tranche atteinte determine le taux applique a la totalite du solde. */
    WHOLE_BALANCE
}
