package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejeu integral : le controle souverain, a l'arrete mensuel.
 *
 * <p>Chaque nuit, l'equilibre et les soldes sont controles sur la journee, par recurrence depuis
 * le dernier cliche verifie. Ici la recurrence est refermee : le journal est rejoue depuis
 * l'origine, entite entiere, et les sous-livres sont rapproches une fois de plus. Un ecart
 * bloque la cloture — un mois ne se clot pas sur une balance qu'on n'a pas rejouee.
 */
public final class FullReconciliationStep implements TfjStep {

    private final Database database;
    private final List<Reconciliation.Check> checks;

    public FullReconciliationStep(Database database, List<Reconciliation.Check> checks) {
        this.database = database;
        this.checks = List.copyOf(checks);
    }

    @Override
    public String name() {
        return "FULL_RECONCILIATION";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<Reconciliation.Discrepancy> discrepancies = database.inTransaction(c -> {
            List<Reconciliation.Discrepancy> found = new ArrayList<>(
                Reconciliation.allBlockingChecks(c, context.legalEntityId()));
            for (Reconciliation.Check check : checks) {
                found.addAll(check.run(c, context.legalEntityId(), context.businessDate(), null));
            }
            return found;
        });
        return new StepResult(3 + checks.size(), 0,
            discrepancies.stream().map(Reconciliation.Discrepancy::toString).toList());
    }
}
