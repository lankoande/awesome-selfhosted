package io.corebanking.loan.service;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.loan.LoanTerms;
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
    Status status,
    LoanTerms terms,
    UUID branchId) {

    public enum Status { DRAFT, ACTIVE, CLOSED, WRITTEN_OFF }

    /**
     * Conditions financieres du contrat, exigees.
     *
     * <p>Elles sont enregistrees au deblocage. Leur absence signale un contrat non debloque, ou
     * repris d'un systeme tiers sans ses conditions : dans les deux cas, reconstruire un
     * echeancier reviendrait a en inventer les termes.
     */
    public LoanTerms requireTerms() {
        if (terms == null) {
            throw new IllegalStateException(
                "Le contrat " + reference + " ne porte pas ses conditions financieres : il n'a pas"
                + " ete debloque par ce socle. Reconstruire son echeancier reviendrait a en"
                + " inventer les termes.");
        }
        return terms;
    }
}
