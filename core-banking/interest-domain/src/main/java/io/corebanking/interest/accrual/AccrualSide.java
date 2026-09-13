package io.corebanking.interest.accrual;

import io.corebanking.kernel.money.Money;

/**
 * Cote du solde remunere : la methode des echelles separe les nombres crediteurs des nombres
 * debiteurs. Un meme compte courant genere des interets crediteurs sur ses jours positifs et des
 * agios sur ses jours negatifs, a des taux differents et sur des comptes de resultat differents.
 * Les deux calculs sont donc distincts et ne se compensent jamais.
 */
public enum AccrualSide {

    /** Jours a solde positif. Le solde negatif est ignore, pas soustrait. */
    CREDITOR {
        @Override
        public Money basis(Money balance) {
            return balance.isPositive() ? balance : Money.zero(balance.currency());
        }
    },

    /** Jours a solde negatif, en valeur absolue : assiette des agios. */
    DEBTOR {
        @Override
        public Money basis(Money balance) {
            return balance.isNegative() ? balance.negate() : Money.zero(balance.currency());
        }
    };

    public abstract Money basis(Money balance);
}
