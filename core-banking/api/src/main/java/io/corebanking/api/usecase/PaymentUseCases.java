package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.Limits;
import io.corebanking.deposits.PaymentService;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.UUID;

/**
 * Paiements sortants et plafonds. L'ordre est plafonne par role comme un virement ; son suivi
 * est un acte de back-office ; sa lecture est tracee. Un plafond de compte se lit dans l'agence
 * du compte, et se pose a deux ({@code DualControlHandlers}).
 */
public final class PaymentUseCases {

    private PaymentUseCases() {}

    public static final class Order implements UseCase<PaymentService.Order, PaymentService.Placed> {
        private final PaymentService payments;

        public Order(PaymentService payments) {
            this.payments = payments;
        }

        @Override public Operation operation() { return Operation.PAYMENT_ORDER; }

        @Override
        public AccessTarget targetOf(PaymentService.Order command) {
            return AccessTarget.inEntity(command.legalEntityId()).withAmount(command.amount());
        }

        @Override
        public PaymentService.Placed execute(PaymentService.Order command) {
            return payments.order(command);
        }
    }

    public enum Transition { SEND, SETTLE, RETURN, CANCEL }

    /** @param nostroAccountId pour un reglement ; @param reason pour un retour ou une annulation */
    public record Step(UUID legalEntityId, UUID orderId, Transition transition,
                       UUID nostroAccountId, String reason, UUID actorId) {}

    /** Envoi, reglement, retour, annulation : un acte de back-office sur un ordre de l'entite. */
    public static final class Process implements UseCase<Step, PaymentService.PaymentOrder> {
        private final PaymentService payments;
        private final Database database;

        public Process(PaymentService payments, Database database) {
            this.payments = payments;
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PAYMENT_PROCESS; }

        @Override
        public AccessTarget targetOf(Step step) {
            return AccessTarget.inEntity(require(database, step.legalEntityId(), step.orderId())
                                             .legalEntityId());
        }

        @Override
        public PaymentService.PaymentOrder execute(Step step) {
            return switch (step.transition()) {
                case SEND -> payments.send(step.orderId(), step.actorId());
                case SETTLE -> {
                    if (step.nostroAccountId() == null) {
                        throw new IllegalArgumentException("Champ obligatoire absent : nostroAccountId");
                    }
                    yield payments.settle(step.orderId(), step.nostroAccountId(), step.actorId());
                }
                case RETURN -> payments.returnOrder(step.orderId(), step.reason(), step.actorId());
                case CANCEL -> payments.cancel(step.orderId(), step.reason(), step.actorId());
            };
        }
    }

    public record Lookup(UUID legalEntityId, UUID orderId) {}

    public static final class Read implements UseCase<Lookup, PaymentService.PaymentOrder> {
        private final Database database;

        public Read(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PAYMENT_READ; }

        @Override
        public AccessTarget targetOf(Lookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public PaymentService.PaymentOrder execute(Lookup query) {
            return require(database, query.legalEntityId(), query.orderId());
        }
    }

    public record Query(UUID legalEntityId, String status, Paging.PageRequest page) {}

    public static final class List_ implements UseCase<Query, Paging.Paged<PaymentService.PaymentOrder>> {
        private final Database database;

        public List_(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.PAYMENT_READ; }

        @Override
        public AccessTarget targetOf(Query query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Paged<PaymentService.PaymentOrder> execute(Query query) {
            String status = query.status() == null || query.status().isBlank() ? null
                : query.status().trim().toUpperCase(java.util.Locale.ROOT);
            return database.inTransaction(c -> new Paging.Paged<>(
                PaymentService.page(c, query.legalEntityId(), status, query.page().offset(),
                                    query.page().size()),
                query.page(), PaymentService.count(c, query.legalEntityId(), status)));
        }
    }

    static PaymentService.PaymentOrder require(Database database, UUID legalEntityId, UUID orderId) {
        return database.inTransaction(c -> PaymentService.find(c, orderId))
            .filter(order -> order.legalEntityId().equals(legalEntityId))
            .orElseThrow(() -> new PaymentService.UnknownPaymentOrderException(orderId));
    }

    // ------------------------------------------------------------------ plafonds

    public record LimitsQuery(UUID accountId) {}

    /** Les plafonds propres a un compte ; ceux du produit se lisent sur le produit. */
    public static final class ReadLimits implements UseCase<LimitsQuery, List<Limits.AccountLimit>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadLimits(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_LIMIT_MANAGE; }

        @Override
        public AccessTarget targetOf(LimitsQuery query) {
            Account account = accounts.require(query.accountId());
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public List<Limits.AccountLimit> execute(LimitsQuery query) {
            return database.inTransaction(c -> Limits.ofAccount(c, query.accountId()));
        }
    }
}
