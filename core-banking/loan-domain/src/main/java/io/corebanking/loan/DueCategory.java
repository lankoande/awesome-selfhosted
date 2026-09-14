package io.corebanking.loan;

/**
 * Nature d'une creance exigible sur un credit.
 *
 * <p>L'ordre de declaration n'a <b>aucune valeur normative</b> : l'ordre d'imputation est un
 * parametre du produit ({@link AllocationOrder}), parce qu'il a un effet financier direct et qu'il
 * est parfois impose par la reglementation locale.
 */
public enum DueCategory {

    /** Frais engages pour recouvrer : mise en demeure, huissier, frais de justice. */
    RECOVERY_FEES,

    /** Penalites de retard, forfaitaires ou proportionnelles. */
    PENALTIES,

    /** Commissions et primes d'assurance echues. */
    FEES_AND_INSURANCE,

    /** Interets de retard courus sur les sommes impayees. */
    LATE_INTEREST,

    /** Interets contractuels echus, taxes comprises. */
    INTEREST,

    /** Capital echu et non regle. */
    PRINCIPAL,

    /**
     * Capital non echu : le solde d'un reglement qui a couvert tout l'exigible s'impute ici, en
     * remboursement anticipe. C'est la seule categorie qui ne correspond pas a une dette echue, et
     * elle vient toujours en dernier — imputer un excedent sur du capital non echu avant d'avoir
     * solde les impayes reviendrait a laisser courir des penalites sur un compte qui a paye.
     */
    FUTURE_PRINCIPAL
}
