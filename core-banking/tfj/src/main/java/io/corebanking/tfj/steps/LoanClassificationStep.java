package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanClassificationService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Classification des credits, provisionnement et suspension des interets.
 *
 * <p>L'etape vient apres les charges de retard : elle classe sur l'etat des impayes tel qu'il
 * ressort de la journee, et sa decision commande la constatation des interets du <b>lendemain</b>.
 * Classer avant d'avoir constate les impayes du jour serait circulaire ; classer avant de
 * suspendre les interets du meme jour ne l'est pas moins, puisque la suspension depend de la
 * classe qu'on est en train d'etablir.
 *
 * <h2>Pourquoi l'etape est bloquante</h2>
 *
 * <p>Un portefeuille non classe est un portefeuille non provisionne. L'arrete reste equilibre, les
 * controles passent, et le defaut ne se voit qu'au moment ou le superviseur demande l'etat des
 * creances en souffrance. Entre-temps, le resultat publie est faux.
 */
public final class LoanClassificationStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanClassificationService classificationService;

    public LoanClassificationStep(LoanClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    @Override
    public String name() {
        return "LOAN_CLASSIFICATION";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanClassificationService.Outcome outcome = classificationService.classify(
            context.legalEntityId(), context.businessDate(), context.actorId(), context.runId());

        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le profil de risque et relancer.");
        }
        return new StepResult(outcome.contractsExamined(), outcome.downgraded(), anomalies);
    }
}
