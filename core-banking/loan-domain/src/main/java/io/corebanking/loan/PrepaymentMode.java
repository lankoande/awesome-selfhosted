package io.corebanking.loan;

/**
 * Effet d'un remboursement anticipe partiel sur l'echeancier restant.
 *
 * <p>Le choix appartient a l'emprunteur, pas a la banque, et il change beaucoup de choses : a
 * capital egal rembourse par anticipation, reduire la duree fait economiser bien plus d'interets
 * que reduire l'echeance. Ne proposer que l'un des deux est un defaut fonctionnel courant.
 */
public enum PrepaymentMode {

    /**
     * La duree est raccourcie autant que possible, l'echeance restant <b>au plus</b> celle du
     * contrat. C'est l'option la plus economique pour l'emprunteur : le capital s'amortit au meme
     * rythme et les interets cessent plus tot.
     *
     * <p>L'echeance ne peut pas etre maintenue a l'identique : la duree etant entiere, la derniere
     * duree admissible donne une echeance legerement inferieure a l'ancienne. La retenir plutot
     * que la duree immediatement plus courte evite de reclamer a l'emprunteur davantage que ce a
     * quoi il s'est engage.
     */
    SHORTEN_TERM,

    /**
     * La duree est maintenue, l'echeance abaissee. Elle soulage la tresorerie mensuelle et coute
     * davantage au total.
     */
    REDUCE_INSTALMENT
}
