package io.corebanking.api.usecase;

import io.corebanking.compliance.ActivityProfiles;
import io.corebanking.compliance.AmlAlerts;
import io.corebanking.compliance.MonitoringScenarios;
import io.corebanking.compliance.SuspiciousActivityReports;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * LCB-FT : le parametrage de la surveillance, la file des alertes, les declarations de soupcon.
 *
 * <h2>Pourquoi ces lectures sont a part</h2>
 *
 * <p>Tout ce qui est ici se lit sous {@code AML_READ}, qui n'est ouvert qu'au risque et a l'audit
 * — jamais au guichet, jamais a la gestion de portefeuille. Ce n'est pas une precaution de
 * confort : prevenir la personne surveillee est un delit, et le socle ne doit pas offrir le chemin
 * qui le rendrait possible par inadvertance. La lecture est tracee pour la meme raison.
 *
 * <p>Le perimetre est l'entite, jamais l'agence. Une surveillance tronquee par l'agence ne verrait
 * pas le client qui repartit ses versements sur trois guichets — c'est-a-dire precisement ce qu'il
 * faut voir.
 */
public final class ComplianceUseCases {

    private ComplianceUseCases() {}

    // ------------------------------------------------------------------ scenarios

    public record EntityQuery(UUID legalEntityId) {}

    public static final class ReadScenarios
            implements UseCase<EntityQuery, List<MonitoringScenarios.Scenario>> {
        private final Database database;

        public ReadScenarios(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<MonitoringScenarios.Scenario> execute(EntityQuery query) {
            return database.inTransaction(c -> MonitoringScenarios.all(c, query.legalEntityId()));
        }
    }

    // ------------------------------------------------------------------ alertes

    public record AlertQuery(UUID legalEntityId, String status) {}

    public static final class ReadAlerts implements UseCase<AlertQuery, List<AmlAlerts.Alert>> {
        private final Database database;

        public ReadAlerts(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_READ; }

        @Override
        public AccessTarget targetOf(AlertQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<AmlAlerts.Alert> execute(AlertQuery query) {
            return database.inTransaction(
                c -> AmlAlerts.alerts(c, query.legalEntityId(), query.status()));
        }
    }

    public record OneAlert(UUID legalEntityId, UUID alertId) {}

    public static final class ReadAlert implements UseCase<OneAlert, AmlAlerts.Alert> {
        private final Database database;

        public ReadAlert(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_READ; }

        @Override
        public AccessTarget targetOf(OneAlert query) {
            return AccessTarget.inEntity(
                require(database, query.legalEntityId(), query.alertId()).legalEntityId());
        }

        @Override
        public AmlAlerts.Alert execute(OneAlert query) {
            return database.inTransaction(c -> AmlAlerts.require(c, query.alertId()));
        }
    }

    /**
     * Prise en charge d'une alerte : elle passe a l'instruction et porte le nom de celui qui
     * l'instruit. Un seul suffit — instruire n'est pas decider.
     */
    public record Assignment(UUID legalEntityId, UUID alertId, UUID assignee) {}

    public static final class AssignAlert implements UseCase<Assignment, AmlAlerts.Alert> {
        private final Database database;

        public AssignAlert(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_ALERT_REVIEW; }

        @Override
        public AccessTarget targetOf(Assignment command) {
            return AccessTarget.inEntity(
                require(database, command.legalEntityId(), command.alertId()).legalEntityId());
        }

        @Override
        public AmlAlerts.Alert execute(Assignment command) {
            return database.inTransaction(
                c -> AmlAlerts.assign(c, command.alertId(), command.assignee()));
        }
    }

    /** Classement motive : le motif est la piece que l'inspection viendra lire. */
    public record Closure(UUID legalEntityId, UUID alertId, String reason, UUID closedBy) {}

    public static final class CloseAlert implements UseCase<Closure, AmlAlerts.Alert> {
        private final Database database;

        public CloseAlert(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_ALERT_REVIEW; }

        @Override
        public AccessTarget targetOf(Closure command) {
            return AccessTarget.inEntity(
                require(database, command.legalEntityId(), command.alertId()).legalEntityId());
        }

        @Override
        public AmlAlerts.Alert execute(Closure command) {
            return database.inTransaction(c -> AmlAlerts.close(
                c, command.alertId(), businessDate(c, command.legalEntityId()), command.reason(),
                command.closedBy()));
        }
    }

    // ------------------------------------------------------------------ profil declare

    /** Le profil se recueille au guichet, avec le reste de la connaissance client. */
    public record ProfileDeclaration(UUID legalEntityId, UUID partyId, Money expectedMonthlyCredit,
                                     Money expectedMonthlyDebit, UUID declaredBy) {}

    public static final class DeclareProfile
            implements UseCase<ProfileDeclaration, ActivityProfiles.Profile> {
        private final Database database;

        public DeclareProfile(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_PROFILE_DECLARE; }

        @Override
        public AccessTarget targetOf(ProfileDeclaration command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public ActivityProfiles.Profile execute(ProfileDeclaration command) {
            return database.inTransaction(c -> {
                ActivityProfiles.declare(c, command.partyId(), command.expectedMonthlyCredit(),
                                         command.expectedMonthlyDebit(),
                                         businessDate(c, command.legalEntityId()),
                                         command.declaredBy());
                return ActivityProfiles.find(c, command.partyId()).orElseThrow();
            });
        }
    }

    // ------------------------------------------------------------------ declarations

    public static final class ReadReports
            implements UseCase<EntityQuery, List<SuspiciousActivityReports.Report>> {
        private final Database database;

        public ReadReports(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<SuspiciousActivityReports.Report> execute(EntityQuery query) {
            return database.inTransaction(
                c -> SuspiciousActivityReports.reports(c, query.legalEntityId()));
        }
    }

    /**
     * Transmission effective : la date du depot et la reference rendue par la cellule.
     *
     * <p>Elle ne se decide pas a deux — la decision a ete prise en redigeant la declaration. Ce
     * qui s'enregistre ici est un fait accompli, et c'est la preuve du depot.
     */
    public record Transmission(UUID legalEntityId, UUID reportId, LocalDate transmittedOn,
                               String reference) {}

    public static final class TransmitReport
            implements UseCase<Transmission, SuspiciousActivityReports.Report> {
        private final Database database;

        public TransmitReport(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.AML_REPORT; }

        @Override
        public AccessTarget targetOf(Transmission command) {
            SuspiciousActivityReports.Report report = database.inTransaction(
                c -> SuspiciousActivityReports.require(c, command.reportId()));
            if (!report.legalEntityId().equals(command.legalEntityId())) {
                throw new IllegalArgumentException("Declaration inconnue : " + command.reportId());
            }
            return AccessTarget.inEntity(report.legalEntityId());
        }

        @Override
        public SuspiciousActivityReports.Report execute(Transmission command) {
            return database.inTransaction(c -> SuspiciousActivityReports.transmit(
                c, command.reportId(),
                command.transmittedOn() == null ? businessDate(c, command.legalEntityId())
                                                : command.transmittedOn(),
                command.reference()));
        }
    }

    // ------------------------------------------------------------------ outillage

    /** L'alerte releve de l'entite de l'appelant, ou elle n'existe pas pour lui. */
    static AmlAlerts.Alert require(Database database, UUID legalEntityId, UUID alertId) {
        AmlAlerts.Alert alert = database.inTransaction(c -> AmlAlerts.require(c, alertId));
        if (!alert.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Alerte inconnue : " + alertId);
        }
        return alert;
    }

    private static LocalDate businessDate(java.sql.Connection c, UUID legalEntityId) {
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
    }
}
