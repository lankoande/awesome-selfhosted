package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.Instalment;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.Receivable;
import io.corebanking.loan.service.LoanContract;
import io.corebanking.loan.service.LoanService;
import io.corebanking.loan.service.LoanStore;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Credit : contrat, reglement, consultation. Le deblocage et le remboursement anticipe — l'argent
 * sort, ou l'echeancier change — se valident a deux et vivent dans les cas d'usage a double
 * validation ({@code DualControlHandlers}).
 */
public final class LoanUseCases {

    private LoanUseCases() {}

    /**
     * Le contrat, ou une erreur nommee. Sous le cloisonnement par entite, le contrat d'une autre
     * entite n'existe pas pour l'appelant.
     */
    public static LoanContract require(Database database, UUID contractId) {
        return database.inTransaction(c -> LoanStore.findContract(c, contractId))
            .orElseThrow(() -> new UnknownLoanException(contractId));
    }

    public static class UnknownLoanException extends RuntimeException {
        public UnknownLoanException(UUID contractId) {
            super("Contrat de credit inconnu : " + contractId);
        }
    }

    // ------------------------------------------------------------------ contrat

    public record Draft(UUID legalEntityId, String reference, String productCode, String currency,
                        UUID loanAccountId, UUID settlementAccountId, String principal,
                        LocalDate disbursedOn, UUID customerPartyId, UUID actorId) {}

    /** Un contrat se cree dans l'agence de son compte de pret ; il est rattache a son client. */
    public static final class Create implements UseCase<Draft, UUID> {
        private final Database database;
        private final AccountDirectory accounts;

        public Create(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.LOAN_CONTRACT_CREATE; }

        @Override
        public AccessTarget targetOf(Draft draft) {
            if (draft.loanAccountId() == null) {
                throw new IllegalArgumentException("Compte de pret obligatoire");
            }
            Account loan = accounts.require(draft.loanAccountId());
            return AccessTarget.inBranch(draft.legalEntityId(), loan.branchId());
        }

        @Override
        public UUID execute(Draft draft) {
            if (draft.principal() == null || draft.principal().isBlank()) {
                throw new IllegalArgumentException("Montant du credit obligatoire");
            }
            if (draft.settlementAccountId() == null) {
                throw new IllegalArgumentException("Compte de reglement obligatoire");
            }
            return database.inTransaction(c -> {
                CurrencyRef currency = AccountUseCases.currency(c, draft.currency());
                LocalDate disbursedOn = draft.disbursedOn() != null ? draft.disbursedOn()
                    : AccountUseCases.businessDate(c, draft.legalEntityId());
                UUID id = LoanStore.createContract(c, new LoanStore.ContractDraft(
                    draft.legalEntityId(), draft.reference(), draft.productCode(), currency,
                    draft.loanAccountId(), draft.settlementAccountId(),
                    Money.of(new BigDecimal(draft.principal()), currency), disbursedOn,
                    draft.actorId()));
                if (draft.customerPartyId() != null) {
                    LoanStore.assignCustomer(c, id, draft.customerPartyId());
                }
                return id;
            });
        }
    }

    // ------------------------------------------------------------------ deblocage (a deux)

    /** Ce que rend un deblocage : l'echeancier publie. */
    public record Disbursed(UUID contractId, UUID scheduleId, List<Instalment> schedule) {}

    // ------------------------------------------------------------------ reglement

    public record Repayment(UUID contractId, String amount, String currency, LocalDate valueDate,
                            IdempotencyKey key, UUID actorId) {}

    /**
     * Reglement recu au guichet, impute sur les creances ouvertes du contrat, la plus ancienne
     * d'abord. L'excedent n'est pas consomme : il est rendu, et c'est a l'agent de decider s'il
     * fait l'objet d'un remboursement anticipe.
     */
    public static final class Repay implements UseCase<Repayment, LoanService.Settlement> {
        private final Database database;
        private final LoanService loans;

        public Repay(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public Operation operation() { return Operation.LOAN_REPAYMENT; }

        @Override
        public AccessTarget targetOf(Repayment command) {
            LoanContract contract = require(database, command.contractId());
            return AccessTarget.inBranch(contract.legalEntityId(), contract.branchId())
                .withAmount(amount(contract, command.amount(), command.currency()));
        }

        @Override
        public LoanService.Settlement execute(Repayment command) {
            LoanContract contract = require(database, command.contractId());
            Money amount = amount(contract, command.amount(), command.currency());
            LocalDate valueDate = command.valueDate() != null ? command.valueDate()
                : database.inTransaction(c -> AccountUseCases.businessDate(c, contract.legalEntityId()));
            // Un reglement recu par l'API est manuel ; le prelevement d'office est l'affaire du TFJ.
            return loans.settle(contract.id(), amount, valueDate, "MANUAL", command.key(),
                                command.actorId(), null);
        }
    }

    public static Money amount(LoanContract contract, String amount, String currency) {
        return Amounts.in(amount, currency, contract.currency(),
                          "Le contrat " + contract.reference());
    }

    // ------------------------------------------------------------------ consultation

    public record Lookup(UUID contractId) {}

    /** Le dossier tel que l'agent le lit : contrat, conditions, echeancier en vigueur, creances. */
    public record LoanView(UUID id, UUID legalEntityId, String reference, String productCode,
                           String currency, UUID loanAccountId, UUID settlementAccountId,
                           Money principal, LocalDate disbursedOn, String status, UUID branchId,
                           LoanTerms terms, List<LoanStore.DueLine> schedule,
                           List<Receivable> receivables, long daysPastDue, LocalDate asOf) {}

    public static final class Read implements UseCase<Lookup, LoanView> {
        private final Database database;
        private final LoanService loans;

        public Read(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public Operation operation() { return Operation.LOAN_READ; }

        @Override
        public AccessTarget targetOf(Lookup query) {
            return AccessTarget.inEntity(require(database, query.contractId()).legalEntityId());
        }

        @Override
        public LoanView execute(Lookup query) {
            LoanContract contract = require(database, query.contractId());
            return database.inTransaction(c -> {
                LocalDate asOf = AccountUseCases.businessDate(c, contract.legalEntityId());
                List<LoanStore.DueLine> schedule =
                    LoanStore.currentSchedule(c, contract.id(), contract.currency());
                List<Receivable> receivables =
                    LoanStore.openReceivables(c, contract.id(), contract.currency());
                long daysPastDue = contract.status() == LoanContract.Status.ACTIVE
                    ? loans.daysPastDue(contract.id(), asOf) : 0;
                return new LoanView(contract.id(), contract.legalEntityId(), contract.reference(),
                                    contract.productCode(), contract.currency().code(),
                                    contract.loanAccountId(), contract.settlementAccountId(),
                                    contract.principal(), contract.disbursedOn(),
                                    contract.status().name(), contract.branchId(),
                                    contract.terms(), schedule, receivables, daysPastDue, asOf);
            });
        }
    }
}
