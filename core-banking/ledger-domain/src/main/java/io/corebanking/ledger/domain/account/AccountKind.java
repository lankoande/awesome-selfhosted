package io.corebanking.ledger.domain.account;

/**
 * Nature d'un compte imputable.
 *
 * <p>Comptes clients et comptes generaux sont deux valeurs du meme type : ils vivent dans le meme
 * journal et obeissent aux memes invariants. C'est le choix fondateur du socle — la comptabilite
 * generale devient une vue agregee du ledger et non une base parallele a reconcilier.
 */
public enum AccountKind {
    /** Compte de la clientele, rattache a un contrat. */
    CUSTOMER,
    /** Compte general du plan comptable. */
    GL,
    /** Compte technique interne (liaison, contrepartie). */
    INTERNAL,
    /** Compte de correspondant bancaire. */
    NOSTRO,
    /** Compte d'attente, dont le solde doit etre justifie a chaque TFJ. */
    SUSPENSE,
    /** Position de change, revalorisee a chaque arrete. */
    POSITION
}
