package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Flux de tresorerie a date calendaire, avant conversion sur l'axe des periodes.
 *
 * <p>Le sens — recu ou paye — n'est pas porte par le flux mais par la liste dans laquelle il
 * figure. Un montant negatif serait la meme information ecrite deux fois, et la premiere erreur de
 * signe serait indetectable.
 */
public record DatedFlow(LocalDate on, Money amount) {

    public DatedFlow {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                "Flux negatif " + amount + " : le sens d'un flux est porte par sa place dans la "
                + "sequence, jamais par son signe.");
        }
    }
}
