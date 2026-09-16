package io.corebanking.tfj.steps;

import io.corebanking.deposits.TermDepositService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Echeances des depots a terme : service des interets, et terme du contrat.
 *
 * <p>L'etape suit la constatation des interets : ce qui est servi au client est ce qui a ete
 * constate, journee du terme comprise. Elle est bloquante — un terme non denoue laisse l'argent
 * du client bloque un jour de plus, et c'est a la banque qu'il le reclamera.
 */
public final class TermDepositMaturityStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;
    private final TermDepositService termDeposits;

    public TermDepositMaturityStep(Database database, TermDepositService termDeposits) {
        this.database = database;
        this.termDeposits = termDeposits;
    }

    @Override
    public String name() {
        return "TERM_DEPOSIT_MATURITY";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> due = database.inTransaction(
            c -> TermDepositService.due(c, context.legalEntityId(), context.businessDate()));
        if (due.isEmpty()) {
            return StepResult.none();
        }
        List<String> anomalies = new ArrayList<>();
        long settled = 0;
        for (UUID id : due) {
            try {
                if (termDeposits.settle(id, context.runId(), context.actorId()) != null) {
                    settled++;
                }
            } catch (RuntimeException e) {
                if (anomalies.size() < MAX_REPORTED) {
                    anomalies.add("depot a terme " + id + " : " + e.getMessage());
                } else if (anomalies.size() == MAX_REPORTED) {
                    anomalies.add("... liste tronquee ; corriger et relancer.");
                }
            }
        }
        return new StepResult(due.size(), settled, anomalies);
    }
}
