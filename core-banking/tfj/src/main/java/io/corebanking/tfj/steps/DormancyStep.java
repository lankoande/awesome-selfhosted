package io.corebanking.tfj.steps;

import io.corebanking.deposits.Dormancy;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;
import java.util.UUID;

/**
 * Mise en dormance des comptes sans operation du client depuis le delai que leur produit fixe.
 *
 * <p>Non bloquante : elle ne comptabilise rien, et un compte passe dormant une nuit plus tard
 * n'a aucune consequence comptable. Elle est defaite avec l'arrete.
 */
public final class DormancyStep implements TfjStep {

    private final Database database;

    public DormancyStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "DORMANCY";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> dormant = database.inTransaction(c -> Dormancy.detect(
            c, context.legalEntityId(), context.businessDate(), context.runId(),
            context.actorId()));
        return StepResult.of(dormant.size(), dormant.size());
    }
}
