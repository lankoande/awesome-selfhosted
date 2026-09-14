package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Echeances de credit du jour : exigibilite et prelevement.
 *
 * <p>Les echeances echues a la date traitee deviennent des creances, et les charges de l'echeance
 * sont constatees en produits. Le capital, lui, ne bouge pas : il est a l'actif depuis le
 * deblocage, et ne s'amortit qu'au reglement.
 *
 * <p>Le prelevement automatique suit immediatement, lorsque le produit le prevoit. Le faire dans la
 * meme etape n'est pas une commodite : entre l'exigibilite et le prelevement, un compte a jour
 * apparaitrait en impaye. Sur un TFJ interrompu entre les deux etapes, ce faux impaye survivrait a
 * la nuit et declencherait des relances.
 *
 * <h2>Pourquoi l'etape est bloquante</h2>
 *
 * <p>Une echeance qui n'est pas rendue exigible n'est jamais reclamee : le client ne doit rien, le
 * compteur de jours de retard ne demarre pas, le credit n'est pas declasse et la provision n'est
 * pas dotee. Rien de tout cela n'apparait dans un controle comptable — l'arrete reste equilibre.
 *
 * <p>Une provision insuffisante, en revanche, n'est pas une anomalie : c'est le fait de gestion que
 * l'etape est faite pour constater.
 */
public final class LoanScheduleStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanService loanService;

    public LoanScheduleStep(LoanService loanService) {
        this.loanService = loanService;
    }

    @Override
    public String name() {
        return "LOAN_SCHEDULE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanService.DueOutcome outcome = loanService.makeDue(
            context.legalEntityId(), context.businessDate(), context.actorId(), context.runId());

        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage des credits et relancer.");
        }
        return new StepResult(outcome.contractsExamined(), outcome.instalmentsMadeDue(), anomalies);
    }
}
