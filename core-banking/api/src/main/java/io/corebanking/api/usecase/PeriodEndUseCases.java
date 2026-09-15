package io.corebanking.api.usecase;

import io.corebanking.api.config.EodEngines;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import io.corebanking.tfj.RunType;
import io.corebanking.tfj.TfjRun;
import java.util.UUID;

/**
 * Consultation des arretes mensuels et annuels, et des exercices. Leur lancement, leur reprise et
 * leur annulation se valident a deux ({@code DualControlHandlers}).
 */
public final class PeriodEndUseCases {

    private PeriodEndUseCases() {}

    public record Lookup(UUID legalEntityId, UUID runId) {}

    /** Lecture d'un arrete mensuel ({@code TFM}) ou annuel ({@code TFA}) par son identifiant. */
    public static final class Read implements UseCase<Lookup, TfjRun> {
        private final EodEngines engines;
        private final RunType type;

        public Read(EodEngines engines, RunType type) {
            this.engines = engines;
            this.type = type;
        }

        @Override
        public Operation operation() {
            return type == RunType.TFA ? Operation.YEAR_CLOSE : Operation.PERIOD_CLOSE;
        }

        @Override
        public AccessTarget targetOf(Lookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public TfjRun execute(Lookup query) {
            return engines.forEntity(query.legalEntityId()).find(query.runId())
                .filter(run -> run.legalEntityId().equals(query.legalEntityId()))
                .orElseThrow(() -> new EodUseCases.UnknownRunException(query.runId()));
        }
    }

    public record FiscalYearQuery(UUID legalEntityId, Paging.PageRequest page) {}

    /** Les exercices de l'entite, du plus ancien au plus recent. */
    public static final class ListFiscalYears
            implements UseCase<FiscalYearQuery, Paging.Paged<FiscalYears.FiscalYear>> {
        private final Database database;

        public ListFiscalYears(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.FISCAL_YEAR_MANAGE; }

        @Override
        public AccessTarget targetOf(FiscalYearQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Paged<FiscalYears.FiscalYear> execute(FiscalYearQuery query) {
            return Paging.Paged.slice(
                database.inTransaction(c -> FiscalYears.ofEntity(c, query.legalEntityId())),
                query.page());
        }
    }
}
