package io.corebanking.api.usecase;

import io.corebanking.calendar.Calendars;
import io.corebanking.deposits.Suspense;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.UUID;

/** Suspens : la politique se lit et se fixe a deux ({@code DualControlHandlers}) ; la revue est tracee. */
public final class SuspenseUseCases {

    private SuspenseUseCases() {}

    public record EntityQuery(UUID legalEntityId) {}

    public static final class ReadPolicies implements UseCase<EntityQuery, List<Suspense.Policy>> {
        private final Database database;

        public ReadPolicies(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.SUSPENSE_MANAGE; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<Suspense.Policy> execute(EntityQuery query) {
            return database.inTransaction(c -> Suspense.policies(c, query.legalEntityId()));
        }
    }

    /** La revue a la date comptable : chaque suspens avec son anciennete, son responsable, son retard. */
    public static final class Review implements UseCase<EntityQuery, List<Suspense.Item>> {
        private final Database database;

        public Review(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.SUSPENSE_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<Suspense.Item> execute(EntityQuery query) {
            var calendar = Calendars.load(database, query.legalEntityId()).calendar();
            return database.inTransaction(c -> Suspense.items(
                c, query.legalEntityId(), AccountUseCases.businessDate(c, query.legalEntityId()),
                calendar));
        }
    }
}
