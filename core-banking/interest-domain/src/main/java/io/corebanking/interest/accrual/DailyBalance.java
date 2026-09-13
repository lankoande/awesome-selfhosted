package io.corebanking.interest.accrual;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Solde d'un compte pour une journee de valeur.
 *
 * <p>C'est <b>la date de valeur</b> qui fait foi, jamais la date comptable. Une operation
 * comptabilisee aujourd'hui avec une date de valeur a J+2 ne porte pas interet avant J+2 ; une
 * operation antidatee en modifie la serie retroactivement.
 */
public record DailyBalance(LocalDate day, Money balance) {

    public DailyBalance {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(balance, "balance");
    }
}
