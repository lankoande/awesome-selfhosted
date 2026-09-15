package io.corebanking.api.web;

import io.corebanking.api.usecase.LedgerUseCases;
import io.corebanking.api.usecase.Paging;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Journal;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Restitutions comptables : balance, totaux, journal de l'entite. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/ledger")
public class LedgerController {

    private final UseCaseExecutor executor;
    private final LedgerUseCases.ReadTrialBalance balance;
    private final LedgerUseCases.ReadTrialBalanceTotals totals;
    private final LedgerUseCases.ReadJournal journal;

    public LedgerController(UseCaseExecutor executor, Database database) {
        this.executor = executor;
        this.balance = new LedgerUseCases.ReadTrialBalance(database);
        this.totals = new LedgerUseCases.ReadTrialBalanceTotals(database);
        this.journal = new LedgerUseCases.ReadJournal(database);
    }

    /** La balance a six colonnes, par pages, dans l'ordre des codes de compte. */
    @GetMapping("/trial-balance")
    public Paging.Paged<LedgerUseCases.BalanceLine> trialBalance(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) AccountKind kind,
            @RequestParam(required = false) UUID branchId,
            Paging.PageRequest page) {
        return executor.run(caller, balance, new LedgerUseCases.BalanceQuery(
            legalEntityId, from, to, kind, branchId, page));
    }

    /** Les totaux de la meme balance, une ligne par devise, avec le constat d'equilibre. */
    @GetMapping("/trial-balance/totals")
    public List<LedgerUseCases.BalanceTotals> trialBalanceTotals(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) AccountKind kind,
            @RequestParam(required = false) UUID branchId) {
        return executor.run(caller, totals, new LedgerUseCases.TotalsQuery(
            legalEntityId, from, to, kind, branchId));
    }

    /** Le journal de l'entite, par curseur : {@code after} est celui de la page precedente. */
    @GetMapping("/journal")
    public Paging.Slice<Journal.StatementLine> journal(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            Paging.CursorRequest cursor) {
        return executor.run(caller, journal, new LedgerUseCases.JournalQuery(
            legalEntityId, from, to, cursor));
    }
}
