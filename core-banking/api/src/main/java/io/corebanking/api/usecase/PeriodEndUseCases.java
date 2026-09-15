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

    public record FiscalYearLookup(UUID legalEntityId, UUID fiscalYearId) {}

    /**
     * Un exercice avec ce que la cloture en a fait : son resultat net une fois clos (positif
     * pour un benefice), l'affectation en vigueur, et toutes les affectations, contre-passees
     * comprises.
     */
    public record FiscalYearView(UUID id, UUID legalEntityId, java.time.LocalDate start,
                                 java.time.LocalDate end, UUID resultAccountId, String status,
                                 UUID closedByRunId, io.corebanking.kernel.money.Money netResult,
                                 FiscalYears.AppropriationRecord appropriation,
                                 java.util.List<FiscalYears.AppropriationRecord> appropriations) {}

    public static final class ReadFiscalYear implements UseCase<FiscalYearLookup, FiscalYearView> {
        private final Database database;

        public ReadFiscalYear(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.FISCAL_YEAR_MANAGE; }

        @Override
        public AccessTarget targetOf(FiscalYearLookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public FiscalYearView execute(FiscalYearLookup query) {
            return database.inTransaction(c -> {
                FiscalYears.FiscalYear year = FiscalYears.find(c, query.fiscalYearId())
                    .filter(found -> found.legalEntityId().equals(query.legalEntityId()))
                    .orElseThrow(() -> new FiscalYears.UnknownFiscalYearException(
                        query.fiscalYearId()));
                var appropriations = FiscalYears.appropriations(c, year.id());
                return new FiscalYearView(year.id(), year.legalEntityId(), year.start(),
                    year.end(), year.resultAccountId(), year.status(), year.closedByRunId(),
                    FiscalYears.netResult(c, year).orElse(null),
                    appropriations.stream().filter(a -> !a.reversed()).findFirst().orElse(null),
                    appropriations);
            });
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
