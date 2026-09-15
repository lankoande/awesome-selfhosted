package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.time.LocalDate;
import java.util.List;

/**
 * Cloture de la periode comptable.
 *
 * <p>Derniere etape, et seul mecanisme qui clot une periode : a partir d'ici, aucune ecriture ne
 * peut plus y etre imputee, et le ledger le refuse par construction. La periode suivante est
 * garantie ouverte — elle l'est normalement depuis la premiere bascule de journee du mois.
 */
public final class PeriodCloseStep implements TfjStep {

    private final Database database;

    public PeriodCloseStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "PERIOD_CLOSE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        return database.inTransaction(c -> {
            LocalDate[] bounds = Entities.periodBounds(c, context.legalEntityId(),
                                                       context.businessDate()).orElse(null);
            if (bounds == null) {
                return new StepResult(0, 0, List.of(
                    "Aucune periode comptable ne couvre le " + context.businessDate()));
            }
            if (context.isDryRun()) {
                return StepResult.of(1, 0);
            }
            Entities.closePeriod(c, context.legalEntityId(), bounds[0]);
            Entities.ensurePeriodCovering(c, context.legalEntityId(), bounds[1].plusDays(1));
            return StepResult.of(1, 1);
        });
    }
}
