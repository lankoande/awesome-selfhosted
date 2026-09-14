package io.corebanking.loan;

/**
 * Assiette de la prime d'assurance emprunteur.
 *
 * <p>Le choix n'est pas neutre et se voit sur le cout total du credit. Sur un pret amortissable,
 * l'assiette du capital initial fait payer jusqu'a la derniere echeance une prime assise sur un
 * capital deja rembourse aux trois quarts. C'est une convention repandue, licite, et sensiblement
 * plus chere que l'assiette du capital restant du — raison pour laquelle elle est un parametre
 * explicite du contrat et jamais un defaut.
 */
public enum InsuranceBasis {

    NONE,

    /** Prime constante, assise sur le capital emprunte. */
    INITIAL_PRINCIPAL,

    /** Prime decroissante, assise sur le capital restant du a l'ouverture de la periode. */
    OUTSTANDING_PRINCIPAL
}
