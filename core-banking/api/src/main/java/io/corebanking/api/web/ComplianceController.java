package io.corebanking.api.web;

import io.corebanking.api.usecase.ComplianceUseCases;
import io.corebanking.compliance.ActivityProfiles;
import io.corebanking.compliance.AmlAlerts;
import io.corebanking.compliance.MonitoringScenarios;
import io.corebanking.compliance.SuspiciousActivityReports;
import io.corebanking.kernel.money.Money;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * LCB-FT : scenarios de surveillance, file des alertes, declarations de soupcon.
 *
 * <p>Deux actes se decident a deux, et pour des raisons opposees : declarer un scenario, parce
 * qu'il fixe ce que la banque ne regardera pas ; rediger une declaration de soupcon, parce qu'elle
 * met en cause une personne — et que ne pas la rediger engage la banque autant.
 *
 * <p>Instruire et classer une alerte se font a un. Le classement porte son motif : c'est la seule
 * trace que l'inspection viendra lire, et une alerte classee sans raison ecrite ne se controle
 * pas.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/compliance")
public class ComplianceController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final ComplianceUseCases.ReadScenarios readScenarios;
    private final ComplianceUseCases.ReadAlerts readAlerts;
    private final ComplianceUseCases.ReadAlert readAlert;
    private final ComplianceUseCases.AssignAlert assignAlert;
    private final ComplianceUseCases.CloseAlert closeAlert;
    private final ComplianceUseCases.DeclareProfile declareProfile;
    private final ComplianceUseCases.ReadReports readReports;
    private final ComplianceUseCases.TransmitReport transmitReport;

    public ComplianceController(UseCaseExecutor executor,
                                io.corebanking.ledger.store.Database database,
                                MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readScenarios = new ComplianceUseCases.ReadScenarios(database);
        this.readAlerts = new ComplianceUseCases.ReadAlerts(database);
        this.readAlert = new ComplianceUseCases.ReadAlert(database);
        this.assignAlert = new ComplianceUseCases.AssignAlert(database);
        this.closeAlert = new ComplianceUseCases.CloseAlert(database);
        this.declareProfile = new ComplianceUseCases.DeclareProfile(database);
        this.readReports = new ComplianceUseCases.ReadReports(database);
        this.transmitReport = new ComplianceUseCases.TransmitReport(database);
    }

    // ------------------------------------------------------------------ scenarios

    @PostMapping("/scenarios")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareScenario(Caller caller, @PathVariable UUID legalEntityId,
                                             @RequestBody Requests.MonitoringScenarioRequest body) {
        // La methode et la coherence des parametres se valident a la soumission : un valideur ne
        // doit pas decouvrir un scenario qui ne surveille rien.
        MonitoringScenarios.Method method = method(body.method());
        if (body.code() == null || body.code().isBlank() || body.validFrom() == null
            || body.label() == null || body.label().isBlank()) {
            throw new IllegalArgumentException("Un scenario porte son code, son libelle et sa "
                + "date d'effet");
        }
        MonitoringScenarios.requireParameters(method, body.thresholdAmount(), body.windowDays(),
                                              body.minimumCount(), body.ratio());
        if (body.validTo() != null && body.validTo().isBefore(body.validFrom())) {
            throw new IllegalArgumentException("Un scenario ne cesse pas avant de commencer : "
                + body.validFrom() + " a " + body.validTo());
        }
        return makerChecker.submit(caller, legalEntityId, "AML_SCENARIO_DECLARE", Payloads.of(
            "code", body.code(), "label", body.label(), "method", method.name(),
            "thresholdAmount", body.thresholdAmount() == null ? null
                               : body.thresholdAmount().toPlainString(),
            "windowDays", body.windowDays(), "minimumCount", body.minimumCount(),
            "ratio", body.ratio() == null ? null : body.ratio().toPlainString(),
            "riskRating", rating(body.riskRating()),
            "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/scenarios")
    public List<MonitoringScenarios.Scenario> scenarios(Caller caller,
                                                        @PathVariable UUID legalEntityId) {
        return executor.run(caller, readScenarios,
                            new ComplianceUseCases.EntityQuery(legalEntityId));
    }

    // ------------------------------------------------------------------ alertes

    @GetMapping("/alerts")
    public List<AmlAlerts.Alert> alerts(Caller caller, @PathVariable UUID legalEntityId,
                                        @RequestParam(required = false) String status) {
        return executor.run(caller, readAlerts,
                            new ComplianceUseCases.AlertQuery(legalEntityId, status(status)));
    }

    @GetMapping("/alerts/{alertId}")
    public AmlAlerts.Alert alert(Caller caller, @PathVariable UUID legalEntityId,
                                 @PathVariable UUID alertId) {
        return executor.run(caller, readAlert,
                            new ComplianceUseCases.OneAlert(legalEntityId, alertId));
    }

    /** Prise en charge : l'alerte passe a l'instruction et porte le nom de celui qui l'instruit. */
    @PostMapping("/alerts/{alertId}/assignment")
    public AmlAlerts.Alert assign(Caller caller, @PathVariable UUID legalEntityId,
                                  @PathVariable UUID alertId) {
        return executor.run(caller, assignAlert, new ComplianceUseCases.Assignment(
            legalEntityId, alertId, Callers.actorId(caller)));
    }

    /** Classement motive. Le motif est la piece que l'inspection viendra lire. */
    @PostMapping("/alerts/{alertId}/closure")
    public AmlAlerts.Alert close(Caller caller, @PathVariable UUID legalEntityId,
                                 @PathVariable UUID alertId,
                                 @RequestBody Requests.AlertClosureRequest body) {
        if (body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("Le classement d'une alerte porte son motif");
        }
        return executor.run(caller, closeAlert, new ComplianceUseCases.Closure(
            legalEntityId, alertId, body.reason(), Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ profil declare

    /** Ce que le client a annonce, et contre quoi l'atypie se mesurera. */
    @PostMapping("/parties/{partyId}/activity-profile")
    public ActivityProfiles.Profile declareProfile(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID partyId,
            @RequestBody Requests.ActivityProfileRequest body) {
        if (body.expectedMonthlyCredit() == null || body.expectedMonthlyDebit() == null
            || body.currency() == null) {
            throw new IllegalArgumentException("Un profil declare porte ses deux flux mensuels et "
                + "leur devise");
        }
        io.corebanking.kernel.money.CurrencyRef currency =
            io.corebanking.kernel.money.Currencies.require(body.currency());
        return executor.run(caller, declareProfile, new ComplianceUseCases.ProfileDeclaration(
            legalEntityId, partyId, Money.of(body.expectedMonthlyCredit(), currency),
            Money.of(body.expectedMonthlyDebit(), currency), Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ declarations

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View report(Caller caller, @PathVariable UUID legalEntityId,
                                    @RequestBody Requests.SuspicionReportRequest body) {
        if (body.partyId() == null || body.reference() == null || body.reference().isBlank()
            || body.narrative() == null || body.narrative().isBlank()
            || body.alertIds() == null || body.alertIds().isEmpty()) {
            throw new IllegalArgumentException("Une declaration porte son tiers, sa reference, son "
                + "expose des faits et les alertes qu'elle couvre");
        }
        return makerChecker.submit(caller, legalEntityId, "AML_REPORT_DRAFT", Payloads.of(
            "partyId", body.partyId(), "reference", body.reference(),
            "narrative", body.narrative(),
            "alertIds", body.alertIds().stream().map(UUID::toString)
                            .collect(Collectors.joining(","))));
    }

    @GetMapping("/reports")
    public List<SuspiciousActivityReports.Report> reports(Caller caller,
                                                          @PathVariable UUID legalEntityId) {
        return executor.run(caller, readReports,
                            new ComplianceUseCases.EntityQuery(legalEntityId));
    }

    /** Transmission effective : la reference rendue par la cellule est la preuve du depot. */
    @PostMapping("/reports/{reportId}/transmission")
    public SuspiciousActivityReports.Report transmit(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID reportId,
            @RequestBody Requests.ReportTransmissionRequest body) {
        if (body.reference() == null || body.reference().isBlank()) {
            throw new IllegalArgumentException("La transmission porte la reference rendue par la "
                + "cellule");
        }
        return executor.run(caller, transmitReport, new ComplianceUseCases.Transmission(
            legalEntityId, reportId, body.transmittedOn(), body.reference()));
    }

    // ------------------------------------------------------------------ outillage

    private static MonitoringScenarios.Method method(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : method");
        }
        try {
            return MonitoringScenarios.Method.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Methode de surveillance inconnue : " + value
                + " (CASH_THRESHOLD, STRUCTURING, ATYPICAL_ACTIVITY, DORMANT_REACTIVATION)");
        }
    }

    private static String rating(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return io.corebanking.party.RiskRating.valueOf(
                value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Notation de risque inconnue : " + value);
        }
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "UNDER_REVIEW", "CLOSED", "REPORTED").contains(status)) {
            throw new IllegalArgumentException("Statut d'alerte inconnu : " + value
                + " (OPEN, UNDER_REVIEW, CLOSED, REPORTED)");
        }
        return status;
    }
}
