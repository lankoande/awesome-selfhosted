package io.corebanking.api.web;

import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.api.usecase.OriginationUseCases;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.loan.service.LendingPolicies;
import io.corebanking.loan.service.LoanOrigination;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * Origination : le dossier de credit, de la demande au contrat.
 *
 * <p>Deposer et instruire sont des actes d'agence ; decider est une delegation, mesuree en francs
 * par la politique d'habilitation ; lever une condition suspensive ouvre un versement et se
 * constate a deux. La contractualisation, elle, ne fait qu'executer la decision : c'est le
 * montant accorde qui devient le capital du contrat.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class OriginationController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final Database database;
    private final OriginationUseCases.SubmitApplication submit;
    private final OriginationUseCases.AssessApplication assess;
    private final OriginationUseCases.AddCondition addCondition;
    private final OriginationUseCases.CancelApplication cancel;
    private final OriginationUseCases.ContractApplication contract;
    private final OriginationUseCases.ReadFile readFile;
    private final OriginationUseCases.ReadApplications readApplications;
    private final OriginationUseCases.ReadPolicies readPolicies;

    public OriginationController(UseCaseExecutor executor, Database database,
                                 MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.database = database;
        this.submit = new OriginationUseCases.SubmitApplication(database);
        this.assess = new OriginationUseCases.AssessApplication(database);
        this.addCondition = new OriginationUseCases.AddCondition(database);
        this.cancel = new OriginationUseCases.CancelApplication(database);
        this.contract = new OriginationUseCases.ContractApplication(database);
        this.readFile = new OriginationUseCases.ReadFile(database);
        this.readApplications = new OriginationUseCases.ReadApplications(database);
        this.readPolicies = new OriginationUseCases.ReadPolicies(database);
    }

    // ------------------------------------------------------------------ la demande

    @PostMapping("/loan-applications")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanOrigination.Application apply(Caller caller, @PathVariable UUID legalEntityId,
                                             @RequestBody Requests.LoanApplicationRequest body) {
        if (body.requestedAmount() == null || body.requestedTermMonths() == null) {
            throw new IllegalArgumentException(
                "Une demande porte un montant et une duree : requestedAmount, requestedTermMonths");
        }
        CurrencyRef currency = currency(legalEntityId, body.currency());
        LocalDate on = body.requestedOn() == null ? businessDate(legalEntityId)
                                                  : body.requestedOn();
        return executor.run(caller, submit, new LoanOrigination.Request(
            legalEntityId, Callers.branchId(caller), body.reference(), body.customerId(),
            body.productCode(), Money.of(body.requestedAmount(), currency),
            body.requestedTermMonths(), body.purpose(), on, Callers.actorId(caller)));
    }

    @PostMapping("/loan-applications/{applicationId}/assessment")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanOrigination.Assessment assess(Caller caller, @PathVariable UUID legalEntityId,
                                             @PathVariable UUID applicationId,
                                             @RequestBody Requests.LoanAssessmentRequest body) {
        LoanOrigination.Application application = require(legalEntityId, applicationId);
        CurrencyRef currency = application.currency();
        LocalDate on = body.assessedOn() == null ? businessDate(legalEntityId) : body.assessedOn();
        return executor.run(caller, assess, new LoanOrigination.Instruction(
            applicationId, money(body.monthlyIncome(), currency, "monthlyIncome"),
            money(body.monthlyCharges(), currency, null), money(body.downPayment(), currency, null),
            body.ratePercent(), body.externalScore(), body.scoreSource(), on,
            Callers.actorId(caller)));
    }

    /** La decision, a deux : le plafond du role dit qui peut la prendre. */
    @PostMapping("/loan-applications/{applicationId}/decision")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View decide(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID applicationId,
                                    @RequestBody Requests.LoanDecisionRequest body) {
        require(legalEntityId, applicationId);
        outcome(body.outcome());
        if (body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("Une decision de credit porte son motif");
        }
        return makerChecker.submit(caller, legalEntityId, "LOAN_APPLICATION_DECIDE", Payloads.of(
            "applicationId", applicationId, "outcome", body.outcome().trim().toUpperCase(Locale.ROOT),
            "grantedAmount", body.grantedAmount() == null ? null
                : body.grantedAmount().toPlainString(),
            "grantedTermMonths", body.grantedTermMonths(),
            "grantedRatePercent", body.grantedRatePercent() == null ? null
                : body.grantedRatePercent().toPlainString(),
            "decidedOn", body.decidedOn(), "reason", body.reason(),
            "waiverReason", body.waiverReason()));
    }

    @PostMapping("/loan-applications/{applicationId}/withdrawal")
    public Object withdraw(Caller caller, @PathVariable UUID legalEntityId,
                           @PathVariable UUID applicationId,
                           @RequestBody Requests.LoanApplicationWithdrawal body) {
        LocalDate on = body.on() == null ? businessDate(legalEntityId) : body.on();
        return executor.run(caller, cancel, new OriginationUseCases.Withdrawal(
            legalEntityId, applicationId, on, body.reason(), Callers.actorId(caller)));
    }

    @PostMapping("/loan-applications/{applicationId}/contract")
    @ResponseStatus(HttpStatus.CREATED)
    public OriginationUseCases.Contracted contract(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID applicationId,
            @RequestBody Requests.LoanContractingRequest body) {
        LocalDate on = body.disbursementDate() == null ? businessDate(legalEntityId)
                                                       : body.disbursementDate();
        return executor.run(caller, contract, new OriginationUseCases.Contracting(
            legalEntityId, new LoanOrigination.Contracting(
                applicationId, body.contractReference(), body.loanAccountId(),
                body.settlementAccountId(), on, Callers.actorId(caller))));
    }

    // ------------------------------------------------------------------ les conditions

    @PostMapping("/loan-applications/{applicationId}/conditions")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanOrigination.Condition addCondition(Caller caller, @PathVariable UUID legalEntityId,
                                                  @PathVariable UUID applicationId,
                                                  @RequestBody Requests.LoanConditionRequest body) {
        LocalDate on = businessDate(legalEntityId);
        return executor.run(caller, addCondition, new OriginationUseCases.NewCondition(
            legalEntityId, applicationId, conditionKind(body.kind()), body.description(),
            body.dueOn(), on, Callers.actorId(caller)));
    }

    /** La levee, a deux : elle ouvre un versement. */
    @PostMapping("/loan-conditions/{conditionId}/clearance")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View clear(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID conditionId,
                                   @RequestBody Requests.LoanConditionClearance body) {
        return makerChecker.submit(caller, legalEntityId, "LOAN_CONDITION_CLEAR", Payloads.of(
            "conditionId", conditionId, "clearedOn", body.clearedOn(),
            "evidence", body.evidence()));
    }

    // ------------------------------------------------------------------ la politique

    @PostMapping("/lending-policies")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View setPolicy(Caller caller, @PathVariable UUID legalEntityId,
                                       @RequestBody Requests.LendingPolicyRequest body) {
        if (body.productCode() == null || body.productCode().isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : productCode");
        }
        if (body.validFrom() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
        }
        return makerChecker.submit(caller, legalEntityId, "LENDING_POLICY_SET", Payloads.of(
            "legalEntityId", legalEntityId, "productCode", body.productCode(),
            "maxDebtServiceRatioPercent", body.maxDebtServiceRatioPercent() == null ? null
                : body.maxDebtServiceRatioPercent().toPlainString(),
            "maxAmount", body.maxAmount() == null ? null : body.maxAmount().toPlainString(),
            "maxTermMonths", body.maxTermMonths(),
            "minDownPaymentPercent", body.minDownPaymentPercent() == null ? null
                : body.minDownPaymentPercent().toPlainString(),
            "collateralRequired", Boolean.TRUE.equals(body.collateralRequired()),
            "decisionValidityDays", body.decisionValidityDays(),
            "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/lending-policies")
    public List<LendingPolicies.Policy> policies(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readPolicies,
                            new OriginationUseCases.EntityQuery(legalEntityId, null));
    }

    // ------------------------------------------------------------------ lectures

    @GetMapping("/loan-applications")
    public List<LoanOrigination.Application> applications(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String status) {
        return executor.run(caller, readApplications,
                            new OriginationUseCases.EntityQuery(legalEntityId, status(status)));
    }

    @GetMapping("/loan-applications/{applicationId}")
    public OriginationUseCases.File file(Caller caller, @PathVariable UUID legalEntityId,
                                         @PathVariable UUID applicationId) {
        return executor.run(caller, readFile,
                            new OriginationUseCases.ApplicationQuery(legalEntityId, applicationId));
    }

    // ------------------------------------------------------------------ outillage

    private LoanOrigination.Application require(UUID legalEntityId, UUID applicationId) {
        LoanOrigination.Application application = database.inTransaction(
            c -> LoanOrigination.require(c, applicationId));
        if (!application.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Demande de credit inconnue : " + applicationId);
        }
        return application;
    }

    private LocalDate businessDate(UUID legalEntityId) {
        return database.inTransaction(c -> AccountUseCases.businessDate(c, legalEntityId));
    }

    private CurrencyRef currency(UUID legalEntityId, String code) {
        return code == null || code.isBlank()
            ? database.inTransaction(c -> Entities.functionalCurrency(c, legalEntityId))
            : database.inTransaction(c -> Entities.requireCurrency(c, code.trim().toUpperCase(
                Locale.ROOT)));
    }

    private static Money money(java.math.BigDecimal amount, CurrencyRef currency, String required) {
        if (amount == null) {
            if (required != null) {
                throw new IllegalArgumentException("Champ obligatoire absent : " + required);
            }
            return Money.zero(currency);
        }
        return Money.of(amount, currency);
    }

    private static LoanOrigination.Outcome outcome(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : outcome");
        }
        try {
            return LoanOrigination.Outcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Sens de decision inconnu : " + value
                + " (APPROVED, REJECTED)");
        }
    }

    private static LoanOrigination.ConditionKind conditionKind(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : kind");
        }
        try {
            return LoanOrigination.ConditionKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nature de condition inconnue : " + value
                + " (PRECEDENT retient le versement, SUBSEQUENT non)");
        }
    }

    private static LoanOrigination.Status status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LoanOrigination.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Statut de demande inconnu : " + value + " ("
                + java.util.Arrays.toString(LoanOrigination.Status.values()) + ")");
        }
    }
}
