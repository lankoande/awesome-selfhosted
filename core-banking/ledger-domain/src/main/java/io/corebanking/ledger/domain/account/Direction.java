package io.corebanking.ledger.domain.account;

/**
 * Sens d'imputation d'une ligne d'ecriture.
 *
 * <p>Le montant d'une ligne est <b>toujours positif</b> ; c'est le sens qui porte l'information.
 * Aucun montant negatif n'existe dans le journal. Cette convention elimine l'ambiguite la plus
 * frequente des systemes maison, ou le signe d'un montant finit par dependre du module qui l'a
 * ecrit.
 */
public enum Direction {
    DEBIT, CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
