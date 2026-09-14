package io.corebanking.loan.service;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contrat de credit tel qu'il est conserve.
 *
 * @param loanAccountId       compte portant l'encours a l'actif
 * @param settlementAccountId compte du client, debite des echeances
 */
public record LoanContract(
    UUID id,
    UUID legalEntityId,
    String reference,
    String productCode,
    CurrencyRef currency,
    UUID loanAccountId,
    UUID settlementAccountId,
    Money principal,
    LocalDate disbursedOn,
    Status status) {

    public enum Status { DRAFT, ACTIVE, CLOSED, WRITTEN_OFF }
}
