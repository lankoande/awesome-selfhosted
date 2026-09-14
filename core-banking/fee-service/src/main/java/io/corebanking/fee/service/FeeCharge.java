package io.corebanking.fee.service;

import io.corebanking.fee.FeePeriod;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Liquidation d'une commission pour une periode, telle qu'elle est conservee.
 *
 * <p>Les montants sont figes a la liquidation et ne sont jamais recalcules, y compris lorsqu'une
 * commission reportee est encaissee des mois plus tard sous un tarif different. Le client a ete
 * informe d'un montant ; le refixer a l'encaissement transformerait une creance en revision
 * tarifaire retroactive, et rendrait la taxe declaree irreconciliable avec son assiette.
 *
 * @param generation rang de la tentative de facturation de cette periode. Il vaut zero tant que
 *                   rien n'a ete annule, et s'incremente a chaque annulation de traitement. Il
 *                   entre dans la cle d'idempotence : sans lui, la refacturation qui suit une
 *                   annulation retomberait sur la cle de l'ecriture contre-passee et serait
 *                   avalee comme un rejeu, la commission disparaissant sans aucune trace.
 */
public record FeeCharge(
    UUID id,
    UUID legalEntityId,
    UUID accountId,
    String feeCode,
    FeePeriod period,
    LocalDate chargeDate,
    Money basisAmount,
    Money gross,
    Money net,
    Money tax,
    Money total,
    BigDecimal taxRatePercent,
    int chargedDays,
    FeeOutcome outcome,
    UUID entryId,
    UUID batchRunId,
    int attempts,
    int generation,
    LocalDate settledOn) {

    /** Anciennete de la creance a une date, en jours depuis la date de perception prevue. */
    public long ageInDays(LocalDate at) {
        return java.time.temporal.ChronoUnit.DAYS.between(chargeDate, at);
    }

    public FeeCharge settledAs(FeeOutcome newOutcome, UUID newEntryId, LocalDate on) {
        return new FeeCharge(id, legalEntityId, accountId, feeCode, period, chargeDate, basisAmount,
                             gross, net, tax, total, taxRatePercent, chargedDays, newOutcome,
                             newEntryId, batchRunId, attempts + 1, generation, on);
    }
}
