package io.corebanking.kernel.money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Montant monetaire exact.
 *
 * <p>Deux echelles coexistent, et les confondre est la premiere cause de derive comptable :
 *
 * <ul>
 *   <li><b>l'echelle de comptabilisation</b>, celle de la devise : 0 decimale en XOF. Seul un
 *       montant a cette echelle peut etre impute au journal ({@link #isBookable()}) ;</li>
 *   <li><b>l'echelle interne</b>, {@value #MAX_INTERNAL_SCALE} decimales, utilisee pour les
 *       calculs intermediaires : interets courus quotidiens, prorata temporis, ventilation de
 *       commissions.</li>
 * </ul>
 *
 * <p>La regle est d'accumuler en echelle interne et de n'arrondir qu'au moment de la
 * comptabilisation effective. Arrondir chaque calcul intermediaire produit une derive cumulee
 * qui devient significative a l'echelle d'un portefeuille : sur un solde de 1 200 000 XOF a
 * 3,5 % l'an en ACT/365, arrondir l'accrual chaque jour coute 25 XOF par an et par compte.
 *
 * <p>L'egalite est <b>numerique</b> : {@code 100} et {@code 100,00} sont le meme montant. C'est
 * volontairement different du comportement de {@link BigDecimal#equals(Object)}, qui compare
 * aussi l'echelle et produit des faux negatifs sur des montants identiques.
 */
public final class Money implements Comparable<Money> {

    /** Nombre de decimales conservees pour les calculs intermediaires. */
    public static final int MAX_INTERNAL_SCALE = 5;

    /** Precision des divisions internes, suffisante pour les taux et prorata. */
    private static final MathContext DIVISION_CONTEXT = new MathContext(34, RoundingMode.HALF_EVEN);

    private final BigDecimal amount;
    private final CurrencyRef currency;

    private Money(BigDecimal amount, CurrencyRef currency) {
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        if (amount.scale() > MAX_INTERNAL_SCALE) {
            throw new ScaleExceededException(amount, MAX_INTERNAL_SCALE);
        }
    }

    public static Money of(BigDecimal amount, CurrencyRef currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, CurrencyRef currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(long amount, CurrencyRef currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money zero(CurrencyRef currency) {
        return new Money(BigDecimal.ZERO.setScale(currency.scale()), currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyRef currency() {
        return currency;
    }

    // ---------------------------------------------------------------- arithmetique

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return isNegative() ? negate() : this;
    }

    /**
     * Multiplication par un facteur sans dimension (taux, quotite, nombre de jours).
     * Le resultat est ramene a l'echelle interne : c'est la forme attendue d'un accrual.
     */
    public Money times(BigDecimal factor) {
        return new Money(
            amount.multiply(factor).setScale(MAX_INTERNAL_SCALE, RoundingMode.HALF_EVEN), currency);
    }

    /** Division par un diviseur sans dimension, ramenee a l'echelle interne. */
    public Money dividedBy(BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division d'un montant par zero");
        }
        return new Money(
            amount.divide(divisor, DIVISION_CONTEXT).setScale(MAX_INTERNAL_SCALE, RoundingMode.HALF_EVEN),
            currency);
    }

    // ---------------------------------------------------------------- comptabilisation

    /** Vrai si le montant peut etre impute tel quel : son echelle est celle de la devise. */
    public boolean isBookable() {
        return amount.stripTrailingZeros().scale() <= currency.scale();
    }

    /**
     * Arrondit a l'echelle de comptabilisation de la devise, selon son mode d'arrondi.
     * C'est la seule operation autorisee a perdre de la precision, et elle ne doit intervenir
     * qu'au moment de produire une ecriture.
     */
    public Money roundToCurrency() {
        return new Money(amount.setScale(currency.scale(), currency.roundingMode()), currency);
    }

    /**
     * Ecart d'arrondi : ce que {@link #roundToCurrency()} abandonne. Cet ecart n'est jamais perdu,
     * il est impute sur un compte de difference d'arrondi.
     */
    public Money roundingRemainder() {
        return minus(roundToCurrency());
    }

    // ---------------------------------------------------------------- predicats

    public boolean isZero()     { return amount.signum() == 0; }
    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isNegative() { return amount.signum() < 0; }

    public boolean isGreaterThan(Money other)        { return compareTo(other) > 0; }
    public boolean isGreaterThanOrEqual(Money other) { return compareTo(other) >= 0; }
    public boolean isLessThan(Money other)           { return compareTo(other) < 0; }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, amount.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.code();
    }
}
