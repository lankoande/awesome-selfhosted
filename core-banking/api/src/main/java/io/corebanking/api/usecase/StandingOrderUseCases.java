package io.corebanking.api.usecase;

import io.corebanking.deposits.StandingOrderService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ordres permanents : la mise en place se decide a deux ({@code DualControlHandlers}), la
 * revocation est un droit du client que l'agent enregistre, et les lectures suivent le compte.
 */
public final class StandingOrderUseCases {

    private StandingOrderUseCases() {}

    /** Revocation par le client : ce qui est parti reste parti, rien de plus ne partira. */
    public record Revocation(UUID legalEntityId, UUID standingOrderId, LocalDate on, String reason,
                             UUID actorId) {}

    public static final class Cancel
            implements UseCase<Revocation, StandingOrderService.StandingOrder> {
        private final Database database;
        private final StandingOrderService standingOrders;

        public Cancel(Database database, StandingOrderService standingOrders) {
            this.database = database;
            this.standingOrders = standingOrders;
        }

        @Override public Operation operation() { return Operation.STANDING_ORDER_CANCEL; }

        @Override
        public AccessTarget targetOf(Revocation revocation) {
            StandingOrderService.StandingOrder order = require(database,
                                                                revocation.legalEntityId(),
                                                                revocation.standingOrderId());
            return AccessTarget.inEntity(order.legalEntityId());
        }

        @Override
        public StandingOrderService.StandingOrder execute(Revocation revocation) {
            return standingOrders.cancel(revocation.standingOrderId(), revocation.on(),
                                         revocation.reason(), revocation.actorId());
        }
    }

    public record EntityQuery(UUID legalEntityId, String status) {}

    public static final class ReadOrders
            implements UseCase<EntityQuery, List<StandingOrderService.StandingOrder>> {
        private final Database database;

        public ReadOrders(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.STANDING_ORDER_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<StandingOrderService.StandingOrder> execute(EntityQuery query) {
            return database.inTransaction(
                c -> StandingOrderService.orders(c, query.legalEntityId(), query.status()));
        }
    }

    public record OrderQuery(UUID legalEntityId, UUID standingOrderId) {}

    /** Un ordre et ce que chaque echeance a donne. */
    public record OrderView(StandingOrderService.StandingOrder order,
                            List<StandingOrderService.Execution> executions) {}

    public static final class ReadOrder implements UseCase<OrderQuery, OrderView> {
        private final Database database;

        public ReadOrder(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.STANDING_ORDER_READ; }

        @Override
        public AccessTarget targetOf(OrderQuery query) {
            return AccessTarget.inEntity(
                require(database, query.legalEntityId(), query.standingOrderId()).legalEntityId());
        }

        @Override
        public OrderView execute(OrderQuery query) {
            return database.inTransaction(c -> new OrderView(
                StandingOrderService.require(c, query.standingOrderId()),
                StandingOrderService.executions(c, query.standingOrderId())));
        }
    }

    /** L'ordre releve de l'entite de l'appelant, ou il n'existe pas. */
    static StandingOrderService.StandingOrder require(Database database, UUID legalEntityId,
                                                      UUID standingOrderId) {
        StandingOrderService.StandingOrder order = database.inTransaction(
            c -> StandingOrderService.require(c, standingOrderId));
        if (!order.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Ordre permanent inconnu : " + standingOrderId);
        }
        return order;
    }
}
