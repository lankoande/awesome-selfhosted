package io.corebanking.api.web;

import io.corebanking.api.usecase.LedgerUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.StatementLayouts;
import io.corebanking.ledger.store.Statements;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Etats financiers : la maquette active appliquee au journal, en devise de tenue de compte. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/statements")
public class StatementController {

    private final UseCaseExecutor executor;
    private final LedgerUseCases.ReadStatement statement;
    private final LedgerUseCases.ReadIncomeStatement income;

    public StatementController(UseCaseExecutor executor, Database database) {
        this.executor = executor;
        this.statement = new LedgerUseCases.ReadStatement(database);
        this.income = new LedgerUseCases.ReadIncomeStatement(database);
    }

    @GetMapping("/balance-sheet")
    public Statements.Statement balanceSheet(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return executor.run(caller, statement, new LedgerUseCases.StatementQuery(
            legalEntityId, StatementLayouts.Kind.BALANCE_SHEET, asOf));
    }

    @GetMapping("/off-balance-sheet")
    public Statements.Statement offBalanceSheet(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf) {
        return executor.run(caller, statement, new LedgerUseCases.StatementQuery(
            legalEntityId, StatementLayouts.Kind.OFF_BALANCE_SHEET, asOf));
    }

    /** Les mouvements d'une plage : l'exercice en cours jusqu'a la date comptable, par defaut. */
    @GetMapping("/income-statement")
    public Statements.Statement incomeStatement(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return executor.run(caller, income, new LedgerUseCases.IncomeQuery(legalEntityId, from, to));
    }
}
