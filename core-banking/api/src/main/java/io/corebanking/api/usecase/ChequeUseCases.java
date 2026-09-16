package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.ChequeService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cheques. Le paiement d'un cheque au guichet est un acte de caisse, dans l'agence de la caisse
 * et plafonne par role ; par compensation, un acte de l'entite. La remise est un acte de guichet,
 * son suivi un acte de back-office, l'opposition un acte de gestion du compte ; la lecture est
 * tracee. Le chequier se delivre a deux ({@code DualControlHandlers}).
 */
public final class ChequeUseCases {

    private ChequeUseCases() {}

    public static final class Pay implements UseCase<ChequeService.Payment, ChequeService.Paid> {
        private final ChequeService cheques;
        private final AccountDirectory accounts;

        public Pay(ChequeService cheques, AccountDirectory accounts) {
            this.cheques = cheques;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CHEQUE_PAY; }

        @Override
        public AccessTarget targetOf(ChequeService.Payment payment) {
            if (payment.mode() == ChequeService.PaymentMode.CASH) {
                return OperationUseCases.cashTarget(accounts, payment.legalEntityId(),
                                                    payment.counterpartyAccountId(),
                                                    payment.accountId(), payment.amount());
            }
            return AccessTarget.inEntity(payment.legalEntityId()).withAmount(payment.amount());
        }

        @Override
        public ChequeService.Paid execute(ChequeService.Payment payment) {
            return cheques.pay(payment);
        }
    }

    public record Stop(UUID legalEntityId, UUID accountId, long number,
                       ChequeService.StopReason reason, UUID actorId) {}

    public static final class StopCheque implements UseCase<Stop, ChequeService.Cheque> {
        private final ChequeService cheques;

        public StopCheque(ChequeService cheques) {
            this.cheques = cheques;
        }

        @Override public Operation operation() { return Operation.CHEQUE_STOP; }

        @Override
        public AccessTarget targetOf(Stop stop) {
            return AccessTarget.inEntity(stop.legalEntityId());
        }

        @Override
        public ChequeService.Cheque execute(Stop stop) {
            return cheques.stop(stop.legalEntityId(), stop.accountId(), stop.number(),
                                stop.reason(), stop.actorId());
        }
    }

    public static final class Deposit
            implements UseCase<ChequeService.Deposit, ChequeService.Deposited> {
        private final ChequeService cheques;

        public Deposit(ChequeService cheques) {
            this.cheques = cheques;
        }

        @Override public Operation operation() { return Operation.CHEQUE_DEPOSIT; }

        @Override
        public AccessTarget targetOf(ChequeService.Deposit deposit) {
            return AccessTarget.inEntity(deposit.legalEntityId()).withAmount(deposit.amount());
        }

        @Override
        public ChequeService.Deposited execute(ChequeService.Deposit deposit) {
            return cheques.deposit(deposit);
        }
    }

    public enum Transition { SETTLE, RETURN }

    /** @param nostroAccountId pour un reglement ; @param reason pour un impaye */
    public record Step(UUID legalEntityId, UUID depositId, Transition transition,
                       UUID nostroAccountId, String reason, UUID actorId) {}

    /** Reglement ou impaye d'une remise : un acte de back-office sur une remise de l'entite. */
    public static final class Process implements UseCase<Step, ChequeService.ChequeDeposit> {
        private final ChequeService cheques;
        private final Database database;

        public Process(ChequeService cheques, Database database) {
            this.cheques = cheques;
            this.database = database;
        }

        @Override public Operation operation() { return Operation.CHEQUE_PROCESS; }

        @Override
        public AccessTarget targetOf(Step step) {
            return AccessTarget.inEntity(
                requireDeposit(database, step.legalEntityId(), step.depositId()).legalEntityId());
        }

        @Override
        public ChequeService.ChequeDeposit execute(Step step) {
            return switch (step.transition()) {
                case SETTLE -> {
                    if (step.nostroAccountId() == null) {
                        throw new IllegalArgumentException("Champ obligatoire absent : nostroAccountId");
                    }
                    yield cheques.settleDeposit(step.depositId(), step.nostroAccountId(),
                                                step.actorId());
                }
                case RETURN -> cheques.returnDeposit(step.depositId(), step.reason(), step.actorId());
            };
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Les chequiers, cheques ou incidents d'un compte ; les cheques d'un statut s'il est donne. */
    public record AccountQuery(UUID accountId, String status) {}

    public static final class ReadBooks implements UseCase<AccountQuery, List<ChequeService.Book>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadBooks(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CHEQUE_READ; }

        @Override
        public AccessTarget targetOf(AccountQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public List<ChequeService.Book> execute(AccountQuery query) {
            return database.inTransaction(c -> ChequeService.books(c, query.accountId()));
        }
    }

    public static final class ReadCheques implements UseCase<AccountQuery, List<ChequeService.Cheque>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadCheques(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CHEQUE_READ; }

        @Override
        public AccessTarget targetOf(AccountQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public List<ChequeService.Cheque> execute(AccountQuery query) {
            String status = status(query.status());
            return database.inTransaction(c -> ChequeService.cheques(c, query.accountId(), status));
        }
    }

    public static final class ReadIncidents
            implements UseCase<AccountQuery, List<ChequeService.Incident>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadIncidents(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.CHEQUE_READ; }

        @Override
        public AccessTarget targetOf(AccountQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public List<ChequeService.Incident> execute(AccountQuery query) {
            return database.inTransaction(c -> ChequeService.incidents(c, query.accountId()));
        }
    }

    public record DepositLookup(UUID legalEntityId, UUID depositId) {}

    public static final class ReadDeposit implements UseCase<DepositLookup, ChequeService.ChequeDeposit> {
        private final Database database;

        public ReadDeposit(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.CHEQUE_READ; }

        @Override
        public AccessTarget targetOf(DepositLookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public ChequeService.ChequeDeposit execute(DepositLookup query) {
            return requireDeposit(database, query.legalEntityId(), query.depositId());
        }
    }

    public record DepositQuery(UUID legalEntityId, String status, Paging.PageRequest page) {}

    public static final class ListDeposits
            implements UseCase<DepositQuery, Paging.Paged<ChequeService.ChequeDeposit>> {
        private final Database database;

        public ListDeposits(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.CHEQUE_READ; }

        @Override
        public AccessTarget targetOf(DepositQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Paged<ChequeService.ChequeDeposit> execute(DepositQuery query) {
            String status = status(query.status());
            return database.inTransaction(c -> new Paging.Paged<>(
                ChequeService.deposits(c, query.legalEntityId(), status, query.page().offset(),
                                       query.page().size()),
                query.page(), ChequeService.countDeposits(c, query.legalEntityId(), status)));
        }
    }

    static ChequeService.ChequeDeposit requireDeposit(Database database, UUID legalEntityId,
                                                      UUID depositId) {
        return database.inTransaction(c -> ChequeService.findDeposit(c, depositId))
            .filter(deposit -> deposit.legalEntityId().equals(legalEntityId))
            .orElseThrow(() -> new ChequeService.UnknownChequeDepositException(depositId));
    }

    private static String status(String status) {
        return status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    }

}
