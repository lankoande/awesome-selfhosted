package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.regulatory.RegulatoryDeclarations;
import io.corebanking.regulatory.ReportFilings;
import io.corebanking.regulatory.ReportingService;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Declarations reglementaires : le catalogue, les etats produits, les echeances.
 *
 * <p>Deux actes se distinguent, et l'habilitation les separe : <b>produire</b> un etat est un
 * travail comptable, qu'un seul fait ; <b>transmettre</b> engage la banque devant son
 * superviseur, et ne se decide pas seul. Ne pas transmettre l'engage autant — c'est pourquoi le
 * retard se constate a l'arrete, et se lit ici.
 */
public final class RegulatoryUseCases {

    private RegulatoryUseCases() {}

    public record EntityQuery(UUID legalEntityId) {}

    public static final class ReadDeclarations
            implements UseCase<EntityQuery, List<RegulatoryDeclarations.Declaration>> {
        private final Database database;

        public ReadDeclarations(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<RegulatoryDeclarations.Declaration> execute(EntityQuery query) {
            return database.inTransaction(
                c -> RegulatoryDeclarations.all(c, query.legalEntityId()));
        }
    }

    /** Production : l'etat se calcule sur la periode close, il ne se saisit pas. */
    public record Production(UUID legalEntityId, UUID declarationId, LocalDate periodEnd,
                             UUID producedBy) {}

    public static final class ProduceReport implements UseCase<Production, ReportFilings.Filing> {
        private final Database database;
        private final ReportingService reporting;

        public ProduceReport(Database database, ReportingService reporting) {
            this.database = database;
            this.reporting = reporting;
        }

        @Override public Operation operation() { return Operation.REGULATORY_REPORT_PRODUCE; }

        @Override
        public AccessTarget targetOf(Production command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public ReportFilings.Filing execute(Production command) {
            LocalDate producedOn = businessDate(database, command.legalEntityId());
            UUID id = reporting.produce(command.legalEntityId(), command.declarationId(),
                                        command.periodEnd(), producedOn, command.producedBy());
            return database.inTransaction(c -> ReportFilings.require(c, id));
        }
    }

    public record FilingQuery(UUID legalEntityId, String status) {}

    public static final class ReadFilings
            implements UseCase<FilingQuery, List<ReportFilings.Filing>> {
        private final Database database;

        public ReadFilings(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(FilingQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<ReportFilings.Filing> execute(FilingQuery query) {
            return database.inTransaction(
                c -> ReportFilings.filings(c, query.legalEntityId(), query.status()));
        }
    }

    public record OneFiling(UUID legalEntityId, UUID filingId) {}

    /** L'etat, ses lignes, et ce que donne son recalcul : un ecart est une anomalie. */
    public record FilingView(ReportFilings.Filing filing, List<String> differences) {}

    public static final class ReadFiling implements UseCase<OneFiling, FilingView> {
        private final Database database;
        private final ReportingService reporting;

        public ReadFiling(Database database, ReportingService reporting) {
            this.database = database;
            this.reporting = reporting;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(OneFiling query) {
            return AccessTarget.inEntity(
                require(database, query.legalEntityId(), query.filingId()).legalEntityId());
        }

        @Override
        public FilingView execute(OneFiling query) {
            ReportFilings.Filing filing = database.inTransaction(
                c -> ReportFilings.require(c, query.filingId()));
            // Le recalcul n'a de sens que sur un etat transmis : c'est celui-la qu'on doit
            // pouvoir reproduire devant l'inspection.
            List<String> differences = filing.transmitted()
                ? reporting.differences(query.legalEntityId(), query.filingId())
                : List.of();
            return new FilingView(filing, differences);
        }
    }

    /** Transmission : la date et la reference rendue par le destinataire. */
    public record Transmission(UUID legalEntityId, UUID filingId, LocalDate transmittedOn,
                               String reference, UUID transmittedBy, UUID approvedBy) {}

    public record Cancellation(UUID legalEntityId, UUID filingId, String reason) {}

    public static final class CancelFiling
            implements UseCase<Cancellation, ReportFilings.Filing> {
        private final Database database;

        public CancelFiling(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_REPORT_TRANSMIT; }

        @Override
        public AccessTarget targetOf(Cancellation command) {
            return AccessTarget.inEntity(
                require(database, command.legalEntityId(), command.filingId()).legalEntityId());
        }

        @Override
        public ReportFilings.Filing execute(Cancellation command) {
            LocalDate on = businessDate(database, command.legalEntityId());
            return database.inTransaction(
                c -> ReportFilings.cancel(c, command.filingId(), on, command.reason()));
        }
    }

    /** Consentement du client a la declaration au bureau d'information sur le credit. */
    public record Consent(UUID legalEntityId, UUID partyId, boolean granted, UUID recordedBy) {}

    public static final class RecordConsent implements UseCase<Consent, Boolean> {
        private final Database database;
        private final ReportingService reporting;

        public RecordConsent(Database database, ReportingService reporting) {
            this.database = database;
            this.reporting = reporting;
        }

        @Override public Operation operation() { return Operation.CREDIT_BUREAU_CONSENT; }

        @Override
        public AccessTarget targetOf(Consent command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public Boolean execute(Consent command) {
            LocalDate on = businessDate(database, command.legalEntityId());
            reporting.recordConsent(command.legalEntityId(), command.partyId(), command.granted(),
                                    on, command.recordedBy());
            return command.granted();
        }
    }

    /** Les echeances declaratives depassees a la date comptable. */
    public static final class ReadDeadlines
            implements UseCase<EntityQuery, List<ReportingService.Overdue>> {
        private final Database database;
        private final ReportingService reporting;

        public ReadDeadlines(Database database, ReportingService reporting) {
            this.database = database;
            this.reporting = reporting;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<ReportingService.Overdue> execute(EntityQuery query) {
            return reporting.watch(query.legalEntityId(),
                                   businessDate(database, query.legalEntityId())).overdue();
        }
    }

    // ------------------------------------------------------------------ fiscalite

    public static final class ReadTaxRules
            implements UseCase<EntityQuery, List<io.corebanking.regulatory.TaxRules.Rule>> {
        private final Database database;

        public ReadTaxRules(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<io.corebanking.regulatory.TaxRules.Rule> execute(EntityQuery query) {
            return database.inTransaction(
                c -> io.corebanking.regulatory.TaxRules.all(c, query.legalEntityId()));
        }
    }

    public static final class ReadStatementPacks
            implements UseCase<EntityQuery, List<io.corebanking.regulatory.StatementPacks.Pack>> {
        private final Database database;

        public ReadStatementPacks(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<io.corebanking.regulatory.StatementPacks.Pack> execute(EntityQuery query) {
            return database.inTransaction(
                c -> io.corebanking.regulatory.StatementPacks.all(c, query.legalEntityId()));
        }
    }

    public static final class ReadConsolidationScopes
            implements UseCase<EntityQuery,
                               List<io.corebanking.regulatory.ConsolidationScopes.Scope>> {
        private final Database database;

        public ReadConsolidationScopes(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.REGULATORY_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<io.corebanking.regulatory.ConsolidationScopes.Scope> execute(
                EntityQuery query) {
            return database.inTransaction(
                c -> io.corebanking.regulatory.ConsolidationScopes.all(c, query.legalEntityId()));
        }
    }

    // ------------------------------------------------------------------ outillage

    static ReportFilings.Filing require(Database database, UUID legalEntityId, UUID filingId) {
        ReportFilings.Filing filing = database.inTransaction(
            c -> ReportFilings.require(c, filingId));
        if (!filing.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Etat inconnu : " + filingId);
        }
        return filing;
    }

    static LocalDate businessDate(Database database, UUID legalEntityId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, legalEntityId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                    }
                    return rs.getObject(1, LocalDate.class);
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
            }
        });
    }
}
