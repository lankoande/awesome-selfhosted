package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.util.Objects;

/**
 * Part d'un reglement imputee sur une creance.
 *
 * @param remaining ce qui reste du sur cette creance apres imputation ; nul si elle est soldee
 */
public record Allocation(Receivable receivable, Money amount, Money remaining) {

    public Allocation {
        Objects.requireNonNull(receivable, "receivable");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Imputation de " + amount + " : montant non positif.");
        }
        if (amount.isGreaterThan(receivable.outstanding())) {
            throw new IllegalArgumentException(
                "Imputation de " + amount + " sur une creance de " + receivable.outstanding() + ".");
        }
        remaining = receivable.outstanding().minus(amount);
    }

    public boolean settles() {
        return remaining.isZero();
    }
}
