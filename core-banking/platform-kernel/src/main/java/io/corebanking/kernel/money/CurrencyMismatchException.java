package io.corebanking.kernel.money;

/** Operation arithmetique entre deux devises differentes. */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(CurrencyRef left, CurrencyRef right) {
        super("Operation impossible entre devises differentes : " + left + " et " + right);
    }
}
