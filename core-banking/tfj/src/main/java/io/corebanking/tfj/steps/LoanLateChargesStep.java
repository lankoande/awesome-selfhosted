package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanLateChargesService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Interets de retard et penalites du jour.
 *
 * <p>L'etape suit immediatement l'exigibilite et le prelevement : un compte provisionne a deja ete
 * debite, et n'a donc rien a payer au titre du retard. L'ordre inverse penaliserait un client qui
 * paie.
 *
 * <h2>Pourquoi l'etape est bloquante</h2>
 *
 * <p>Un interet de retard non couru est un produit perdu, et surtout une journee qui ne sera jamais
 * rattrapee : le cumul repart du dernier jour enregistre, et une journee absente du registre
 * n'existe plus. L'echec doit donc arreter le traitement plutot que de le laisser avancer d'un
 * jour.
 */
public final class LoanLateChargesStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanLateChargesService lateService;

    public LoanLateChargesStep(LoanLateChargesService lateService) {
        this.lateService = lateService;
    }

    @Override
    public String name() {
        return "LOAN_LATE_CHARGES";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanLateChargesService.Outcome outcome = lateService.charge(
            context.legalEntityId(), context.businessDate(), context.actorId(), context.runId());

        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage du regime de retard.");
        }
        return new StepResult(outcome.contractsExamined(), outcome.contractsCharged(), anomalies);
    }
}
