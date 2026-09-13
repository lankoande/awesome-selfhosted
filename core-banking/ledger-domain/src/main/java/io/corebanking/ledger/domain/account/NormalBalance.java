package io.corebanking.ledger.domain.account;

/**
 * Sens naturel d'un compte : celui dans lequel son solde est positif.
 *
 * <p>Un compte courant client a un sens naturel CREDIT — la banque doit l'argent au client. Un
 * solde negatif signifie donc un decouvert. Un compte de charges a un sens naturel DEBIT.
 */
public enum NormalBalance {
    DEBIT, CREDIT;

    /** Vrai si une imputation dans ce sens augmente le solde du compte. */
    public boolean increasedBy(Direction direction) {
        return direction.name().equals(name());
    }
}
