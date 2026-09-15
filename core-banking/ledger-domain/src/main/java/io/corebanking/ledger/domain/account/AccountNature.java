package io.corebanking.ledger.domain.account;

/**
 * Ce que le compte represente pour les etats de synthese.
 *
 * <p>La nature est une donnee du compte, pas une convention sur son code : un plan comptable
 * interne ne numerote pas forcement comme le plan de reference, et la cloture annuelle ne peut
 * pas deviner quels comptes sont a solder.
 */
public enum AccountNature {
    /** Actif, passif, capitaux propres : le solde se reporte d'un exercice a l'autre. */
    BALANCE_SHEET,
    /** Charges et produits : soldes sur le compte de resultat a la cloture de l'exercice. */
    PROFIT_AND_LOSS,
    /** Engagements hors bilan : tenus dans le meme ledger, presentes a part. */
    OFF_BALANCE_SHEET
}
