package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FxPositions;
import io.corebanking.ledger.store.FxRates;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Change : les cours se cotent et les positions se declarent a deux
 * ({@code DualControlHandlers}) ; leur lecture est tracee. L'exposition se lit a la date
 * comptable, cours du jour compris.
 */
public final class FxUseCases {

    private FxUseCases() {}

    public record RateQuery(UUID legalEntityId, String currency, Integer limit) {}

    public static final class ReadRates implements UseCase<RateQuery, List<FxRates.Rate>> {
        private final Database database;

        public ReadRates(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.FX_READ; }

        @Override
        public AccessTarget targetOf(RateQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<FxRates.Rate> execute(RateQuery query) {
            String currency = query.currency() == null || query.currency().isBlank() ? null
                : query.currency().trim().toUpperCase(Locale.ROOT);
            int limit = query.limit() == null ? 50 : Math.clamp(query.limit(), 1, 200);
            return database.inTransaction(
                c -> FxRates.rates(c, query.legalEntityId(), currency, limit));
        }
    }

    public record PositionQuery(UUID legalEntityId) {}

    /** Les positions avec leur exposition : solde, contre-valeur portee, cours, ecart latent. */
    public static final class ReadPositions
            implements UseCase<PositionQuery, List<FxPositions.Exposure>> {
        private final Database database;

        public ReadPositions(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.FX_READ; }

        @Override
        public AccessTarget targetOf(PositionQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<FxPositions.Exposure> execute(PositionQuery query) {
            return database.inTransaction(c -> {
                var on = AccountUseCases.businessDate(c, query.legalEntityId());
                List<FxPositions.Exposure> exposures = new ArrayList<>();
                for (FxPositions.Position position : FxPositions.all(c, query.legalEntityId())) {
                    exposures.add(FxPositions.exposure(c, position, on));
                }
                return exposures;
            });
        }
    }
}
