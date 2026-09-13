package io.corebanking.interest.service;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Resultat d'un calcul d'interets courus.
 *
 * @param cumulativePrecise cumul exact depuis l'origine, en precision interne
 * @param postedTotal       cumul reellement impute au journal, arrondi
 * @param postedDelta       montant impute par cette execution
 * @param entryId           ecriture produite, nulle si le delta etait nul
 */
public record AccrualOutcome(
    UUID accountId,
    LocalDate from,
    LocalDate through,
    int generation,
    Money cumulativePrecise,
    Money postedTotal,
    Money postedDelta,
    UUID entryId) {

    public boolean produced() {
        return entryId != null;
    }
}
