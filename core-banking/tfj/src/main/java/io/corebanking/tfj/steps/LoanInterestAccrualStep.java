package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanInterestAccrualService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Interets courus non echus sur credits.
 *
 * <p>Apres l'exigibilite et avant les charges de retard : l'echeance reclamee aujourd'hui voit son
 * etalement complete dans le meme arrete, et la creance qui vient de reprendre les courus les
 * trouve integralement constates. L'etape est bloquante pour la meme raison que les interets sur
 * depots : un credit non etale est un produit sous-evalue que la balance ne voit pas.
 */
public final class LoanInterestAccrualStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanInterestAccrualService service;

    public LoanInterestAccrualStep(LoanInterestAccrualService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "LOAN_INTEREST_ACCRUAL";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanInterestAccrualService.Outcome outcome = service.accrue(
            context.legalEntityId(), context.businessDate(), context.actorId(), context.runId());
        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage des credits et relancer.");
        }
        return new StepResult(outcome.linesExamined(), outcome.linesAccrued(), anomalies);
    }
}
