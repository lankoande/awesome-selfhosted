package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Cloture de l'exercice : derniere etape, apres celle qui a clos son dernier mois. A partir
 * d'ici, l'exercice ne se rouvre que par l'annulation de la cloture annuelle, qui le dit.
 */
public final class FiscalYearCloseStep implements TfjStep {

    private final Database database;

    public FiscalYearCloseStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "FISCAL_YEAR_CLOSE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        return database.inTransaction(c -> {
            FiscalYears.FiscalYear year = FiscalYears.endingOn(c, context.legalEntityId(),
                                                               context.businessDate())
                .orElse(null);
            if (year == null) {
                return new StepResult(0, 0, List.of(
                    "Aucun exercice ne se termine le " + context.businessDate()));
            }
            if (context.isDryRun() || "CLOSED".equals(year.status())) {
                return StepResult.of(1, 0);
            }
            FiscalYears.close(c, year.id(), context.runId());
            return StepResult.of(1, 1);
        });
    }
}
