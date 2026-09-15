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

    public FiscalYearController(UseCaseExecutor executor, Database database,
                                MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.list = new PeriodEndUseCases.ListFiscalYears(database);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View open(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.OpenFiscalYear body) {
        return makerChecker.submit(caller, legalEntityId, "FISCAL_YEAR_OPEN", Payloads.of(
            "start", body.start(), "end", body.end(), "resultAccountId", body.resultAccountId()));
    }

    @GetMapping
    public Paging.Paged<FiscalYears.FiscalYear> list(Caller caller,
                                                     @PathVariable UUID legalEntityId,
                                                     Paging.PageRequest page) {
        return executor.run(caller, list, new PeriodEndUseCases.FiscalYearQuery(legalEntityId, page));
    }
}
