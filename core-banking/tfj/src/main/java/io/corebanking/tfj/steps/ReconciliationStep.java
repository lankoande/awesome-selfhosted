package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Controles d'integrite, bloquants.
 *
 * <p>Le seuil de tolerance est zero, et c'est un choix. La tentation d'en admettre un est forte en
 * exploitation : l'ecart est d'une unite, l'arrete presse, la nuit avancee. Elle est toujours
 * perdante — un ecart admis un soir devient, en fin d'exercice, un ecart dont personne ne retrouve
 * l'origine, et qui se solde par une ecriture d'ajustement inexplicable.
 */
public final class ReconciliationStep implements TfjStep {

    private final Database database;

    public ReconciliationStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "RECONCILIATION";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<Reconciliation.Discrepancy> discrepancies = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, context.legalEntityId()));

        return new StepResult(3, 0,
            discrepancies.stream().map(Reconciliation.Discrepancy::toString).toList());
    }
}
