package io.corebanking.api.web;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Corps des requetes. Les montants arrivent en chaine, avec leur devise, et sont confrontes a la
 * devise du compte avant de devenir un {@link Money} : une devise qui ne correspond pas est un
 * refus, jamais une conversion.
 */
public final class Requests {

    private Requests() {}

    public record Amount(String amount, String currency) {
        public Money on(Account account) {
            if (amount == null || currency == null) {
                throw new IllegalArgumentException("Montant et devise obligatoires");
            }
            if (!account.currency().code().equals(currency)) {
                throw new IllegalArgumentException(
                    "Le compte " + account.code() + " est tenu en " + account.currency().code()
                    + ", la requete est en " + currency);
            }
            return Money.of(new BigDecimal(amount), account.currency());
        }
    }

    /** La caisse n'est pas dans la requete : c'est celle de l'appelant, resolue depuis son jeton. */
    public record CashOperation(String amount, String currency, String channel, String narrative) {
        public Money on(Account account) {
            return new Amount(amount, currency).on(account);
        }
    }

    public record Transfer(UUID sourceAccountId, UUID destinationAccountId, String amount,
                           String currency, String channel, String narrative) {}

    /** Les actes a double validation n'ont pas d'approbateur dans le corps : c'est le checker. */
    public record OpenAccount(String code, UUID holderPartyId, String productCode, String currency) {}

    public record CloseAccount(UUID payoutAccountId) {}

    public record BlockAccount(String kind, String reason, String reference) {}

    public record LiftBlock(String reason) {}

    public record PlaceHold(String amount, String currency, String type, String reference,
                            LocalDate expiresOn) {}

    public record Identifier(String kind, String value, LocalDate issuedOn, LocalDate expiresOn,
                             String issuer) {}

    public record CreateParty(String reference, String kind, String displayName,
                              LocalDate birthOrRegistrationDate, String countryCode, String segment,
                              List<Identifier> identifiers) {}

    public record VerifyKyc(String rating, LocalDate verifiedOn) {}

    public record RunEod(LocalDate businessDate, String mode) {}

    // ------------------------------------------------------------------ credit

    public record CreateLoan(String reference, String productCode, String currency,
                             UUID loanAccountId, UUID settlementAccountId, String principal,
                             LocalDate disbursedOn, UUID customerPartyId) {}

    /**
     * Conditions du deblocage. Tout ce qui n'est pas dit prend la valeur par defaut des
     * conditions de credit : mensuel, annuite constante, ACT/365, sans assurance ni taxe.
     */
    public record Disbursement(String annualRatePercent, String frequency, Integer instalments,
                               Integer graceInstalments, LocalDate firstDueDate, String method,
                               String dayCount, String periodicFee, String insuranceBasis,
                               String insuranceRatePercent, String taxOnInterestPercent,
                               String upfrontFees) {}

    /** Un reglement recu au guichet ; le prelevement d'office, lui, releve du TFJ. */
    public record LoanRepayment(String amount, String currency, LocalDate valueDate) {}

    public record LoanPrepayment(String amount, String currency, String mode) {}

    /**
     * Rechelonnement : nouvelle duree et nouveau calendrier sur le capital non echu, a compter
     * d'une date d'effet ; les conditions financieres restent celles du contrat.
     */
    public record Rescheduling(Integer instalments, LocalDate firstDueDate, LocalDate effectiveFrom,
                               String reason) {}

    // ------------------------------------------------------------------ parametrage

    public record RateTier(String from, String to, String annualRatePercent) {}

    public record ProductDraft(String code, String productType, String label, String currency,
                               LocalDate validFrom, LocalDate validTo,
                               java.util.Map<String, String> parameters, List<RateTier> tiers) {}

    public record ValueDateRuleRequest(String operationType, String channel, String direction,
                                       Integer offset, String unit, String convention,
                                       LocalDate validFrom, LocalDate validTo) {}

    public record Holiday(LocalDate date, String label) {}

    public record CreateBranch(String code, String name, String kind, UUID parentId,
                               LocalDate openedOn, java.util.Map<String, UUID> liaisonAccounts) {}

    public record CreateTill(String code, UUID cashAccountId, String tellerSubjectId,
                             UUID differenceAccountId) {}

    public record TillClosing(String counted, String currency) {}

    // ------------------------------------------------------------------ paiements et plafonds

    public record PaymentOrderRequest(String amount, String currency, String beneficiaryName,
                                      String beneficiaryBank, String beneficiaryAccount,
                                      String reference, String channel) {}

    public record Settlement(UUID nostroAccountId) {}

    public record Reason(String reason) {}

    /** Un plafond de compte : nature (TRANSACTION, DAILY, MONTHLY), montant, validite. */
    public record AccountLimitRequest(String kind, String amount, String currency,
                                      LocalDate validFrom, LocalDate validTo) {}

    // ------------------------------------------------------------------ arretes et exercices

    public record OpenFiscalYear(LocalDate start, LocalDate end, UUID resultAccountId) {}

    /** Une destination du resultat : reserve, report a nouveau, dividendes a payer. */
    public record Allocation(UUID accountId, String amount, String currency) {}

    /**
     * La decision d'affectation du resultat : date comptable de l'ecriture (apres la fin de
     * l'exercice), date de la decision, piece, destinations dont la somme est le resultat.
     */
    public record Appropriation(LocalDate bookingDate, LocalDate decidedOn, String reference,
                                List<Allocation> allocations) {}

    // ------------------------------------------------------------------ risque et suretes

    public record CollateralPolicyDraft(String kind, String label, String eligibleRatePercent,
                                        Integer maxValuationAgeMonths, LocalDate validFrom,
                                        LocalDate validTo) {}

    public record RiskBucketRequest(Integer ordinal, String code, String label, Integer fromDays,
                                    Integer toDays, String provisionRatePercent,
                                    Boolean performing) {}

    public record RiskGridRequest(String code, List<RiskBucketRequest> buckets, String contagion,
                                  String suspendFromBucket, Integer cureDays) {}

    public record RiskProfileDraft(String label, LocalDate validFrom, LocalDate validTo,
                                   RiskGridRequest grid) {}

    public record RegisterCollateral(UUID customerPartyId, String assetReference, String kind,
                                     String label, String assetValue, String securedAmount,
                                     String currency, Integer rank, LocalDate valuedOn) {}

    public record AllocateCollateral(UUID contractId, String sharePercent) {}

    public record ReleaseCollateral(LocalDate on) {}

    // ------------------------------------------------------------------ etats financiers

    public record StatementLineRequest(Integer ordinal, String code, String label, Integer level,
                                       String kind, String side, List<String> plus,
                                       List<String> minus) {}

    public record StatementRuleRequest(Integer ordinal, String lineCode, String accountKind,
                                       String codePrefix, String balanceSide) {}

    public record StatementLayoutDraft(String kind, String code, String label, LocalDate validFrom,
                                       LocalDate validTo, List<StatementLineRequest> lines,
                                       List<StatementRuleRequest> rules) {}

    // ------------------------------------------------------------------ schemas comptables

    public record SchemaLine(String account, String direction, String amount, String label,
                             String condition) {}

    public record SchemaEvent(String eventType, java.util.Map<String, String> derivations,
                              List<SchemaLine> lines) {}

    public record AccountingSchemaDraft(String code, String label, String currency,
                                        LocalDate validFrom, LocalDate validTo, Integer version,
                                        List<SchemaEvent> events) {}

    /** Reponse d'un ferie declare. */
    public record HolidayDeclared(UUID calendarId, LocalDate date, String label) {}

    public record CancelEod(LocalDate reversalBookingDate, String reason) {}

    /** Reponse d'une creation : l'identifiant de ce qui a ete cree. */
    public record Created(UUID id) {}
}
