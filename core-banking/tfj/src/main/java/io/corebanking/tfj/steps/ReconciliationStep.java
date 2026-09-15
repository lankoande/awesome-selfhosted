package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.tfj.Runs;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Controles d'integrite, bloquants.
 *
 * <p>Le seuil de tolerance est zero, et c'est un choix. La tentation d'en admettre un est forte en
 * exploitation : l'ecart est d'une unite, l'arrete presse, la nuit avancee. Elle est toujours
 * perdante — un ecart admis un soir devient, en fin d'exercice, un ecart dont personne ne retrouve
 * l'origine, et qui se solde par une ecriture d'ajustement inexplicable.
 *
 * <h2>Ce qui est controle chaque nuit</h2>
 *
 * <p>Le grand livre de la journee : ses lignes s'equilibrent, le solde materialise egale le cliche
 * du jour, les stripes sont completes. Et les sous-livres : chaque module rapproche ce qu'il
 * affirme detenir — creances, encours, courus, commissions — du solde des comptes ou il l'a
 * impute. Le rejeu integral du journal, lui, est l'affaire de l'arrete mensuel.
 */
public final class ReconciliationStep implements TfjStep {

    private final Database database;
    private final List<Reconciliation.Check> checks;

    public ReconciliationStep(Database database) {
        this(database, List.of());
    }

    public ReconciliationStep(Database database, List<Reconciliation.Check> checks) {
        this.database = database;
        this.checks = List.copyOf(checks);
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
        List<Reconciliation.Discrepancy> discrepancies = database.inTransaction(c -> {
            // Le cliche est rafraichi avant d'etre controle : entre un echec et la reprise, la
            // correction qui repare l'ecart a ete comptabilisee sur la journee, et le cliche
            // arrete a l'etape precedente ne la porte pas encore.
            BalanceSnapshotStep.snapshot(c, context);
            LocalDate previous = Runs.previousCompletedDay(c, context.legalEntityId(),
                                                           context.businessDate()).orElse(null);
            List<Reconciliation.Discrepancy> found = new ArrayList<>(
                Reconciliation.dailyChecks(c, context.legalEntityId(), context.businessDate(),
                                           previous));
            for (Reconciliation.Check check : checks) {
                found.addAll(check.run(c, context.legalEntityId(), context.businessDate(),
                                       context.runId()));
            }
            return found;
        });

        return new StepResult(3 + checks.size(), 0,
            discrepancies.stream().map(Reconciliation.Discrepancy::toString).toList());
    }
}
