package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Creance exigible sur un credit : ce qui reste du au titre d'une echeance et d'une nature.
 *
 * @param instalmentNumber rang de l'echeance d'origine ; zero pour une creance qui ne se rattache a
 *                         aucune echeance, comme des frais de recouvrement
 * @param outstanding      solde restant du, toujours strictement positif : une creance soldee
 *                         n'est plus une creance
 */
public record Receivable(
    UUID id,
    int instalmentNumber,
    DueCategory category,
    LocalDate dueDate,
    Money outstanding) {

    public Receivable {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(outstanding, "outstanding");
        if (!outstanding.isPositive()) {
            throw new IllegalArgumentException(
                "Creance de " + outstanding + " : une creance soldee ou negative n'en est pas une.");
        }
        if (!outstanding.isBookable()) {
            throw new IllegalArgumentException(
                "Creance de " + outstanding + " non imputable en " + outstanding.currency());
        }
        if (instalmentNumber < 0) {
            throw new IllegalArgumentException("Rang d'echeance negatif : " + instalmentNumber);
        }
    }
}
