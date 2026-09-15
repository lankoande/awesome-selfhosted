package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloture des credits qui n'ont plus rien a reclamer.
 *
 * <p>Apres la classification, et pour cette raison : c'est elle qui reprend la provision d'un
 * encours devenu nul. Un credit clos avant elle sortirait du portefeuille classe avec sa provision
 * intacte, et elle ne serait jamais reprise.
 *
 * <p>L'etape est bloquante parce que son anomalie est grave : un encours residuel sur un credit
 * dont toutes les echeances sont reclamees et reglees est un ecart entre le compte de pret et le
 * sous-livre — precisement ce que le rapprochement du grand livre ne voit pas.
 */
public final class LoanClosureStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanService loanService;

    public LoanClosureStep(LoanService loanService) {
        this.loanService = loanService;
    }

    @Override
    public String name() {
        return "LOAN_CLOSURE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanService.ClosureOutcome outcome = loanService.closeSettled(
            context.legalEntityId(), context.businessDate(), context.runId());
        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; rapprocher les comptes de pret et relancer.");
        }
        return new StepResult(outcome.examined(), outcome.closed(), anomalies);
    }
}
