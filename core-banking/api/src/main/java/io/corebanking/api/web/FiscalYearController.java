package io.corebanking.api.web;

import io.corebanking.api.usecase.Paging;
import io.corebanking.api.usecase.PeriodEndUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exercices fiscaux : ouverts a deux, avec leurs bornes et leur compte de resultat. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/fiscal-years")
public class FiscalYearController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final PeriodEndUseCases.ListFiscalYears list;
    private final PeriodEndUseCases.ReadFiscalYear read;

    public FiscalYearController(UseCaseExecutor executor, Database database,
                                MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.list = new PeriodEndUseCases.ListFiscalYears(database);
        this.read = new PeriodEndUseCases.ReadFiscalYear(database);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View open(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.OpenFiscalYear body) {
        return makerChecker.submit(caller, legalEntityId, "FISCAL_YEAR_OPEN", Payloads.of(
            "start", body.start(), "end", body.end(), "resultAccountId", body.resultAccountId()));
    }

    /** L'exercice, son resultat une fois clos, et son affectation. */
    @GetMapping("/{fiscalYearId}")
    public PeriodEndUseCases.FiscalYearView read(Caller caller, @PathVariable UUID legalEntityId,
                                                 @PathVariable UUID fiscalYearId) {
        return executor.run(caller, read,
                            new PeriodEndUseCases.FiscalYearLookup(legalEntityId, fiscalYearId));
    }

    /**
     * L'affectation du resultat : la decision de l'assemblee, demandee par la comptabilite et
     * validee par une seconde. Les destinations sont rendues telles que recues ; c'est
     * l'execution qui les verifie contre le resultat.
     */
    @PostMapping("/{fiscalYearId}/appropriation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View appropriate(Caller caller, @PathVariable UUID legalEntityId,
                                         @PathVariable UUID fiscalYearId,
                                         @RequestBody Requests.Appropriation body) {
        java.util.Map<String, Object> payload = Payloads.of(
            "fiscalYearId", fiscalYearId, "bookingDate", body.bookingDate(),
            "decidedOn", body.decidedOn(), "reference", body.reference());
        java.util.List<java.util.Map<String, Object>> allocations = new java.util.ArrayList<>();
        for (Requests.Allocation allocation : body.allocations() == null
                ? java.util.List.<Requests.Allocation>of() : body.allocations()) {
            allocations.add(Payloads.of("accountId", allocation.accountId(),
                                        "amount", allocation.amount(),
                                        "currency", allocation.currency()));
        }
        payload.put("allocations", allocations);
        return makerChecker.submit(caller, legalEntityId, "RESULT_APPROPRIATE", payload);
    }

    @GetMapping
    public Paging.Paged<FiscalYears.FiscalYear> list(Caller caller,
                                                     @PathVariable UUID legalEntityId,
                                                     Paging.PageRequest page) {
        return executor.run(caller, list, new PeriodEndUseCases.FiscalYearQuery(legalEntityId, page));
    }
}
