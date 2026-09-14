package io.corebanking.interest.service;

import io.corebanking.kernel.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resultat d'un calcul d'interets mene sur un lot de comptes.
 *
 * @param entries   ecritures produites : une par couple de comptes d'imputation, et non une par
 *                  compte remunere
 * @param anomalies comptes ecartes, avec leur motif
 */
public record BatchAccrualOutcome(
    int accountsProcessed,
    int accountsAccrued,
    Map<UUID, Money> deltaByAccount,
    List<UUID> entries,
    List<String> anomalies) {

    public BatchAccrualOutcome {
        deltaByAccount = Map.copyOf(deltaByAccount);
        entries = List.copyOf(entries);
        anomalies = List.copyOf(anomalies);
    }
}
