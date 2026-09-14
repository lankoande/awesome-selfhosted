package io.corebanking.tfj.steps;

import io.corebanking.loan.service.LoanMobilisationService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Mobilisation des credits debloques par tranches : interets intercalaires et cloture.
 *
 * <p>Deux traitements, indissociables parce qu'ils se partagent le calendrier : les interets de la
 * periode de mobilisation echue sont factures, et la mobilisation arrivee a son terme est close —
 * le reliquat non tire tombe, l'echeancier definitif est publie sur le capital reellement verse.
 *
 * <h2>Pourquoi avant les echeances</h2>
 *
 * <p>C'est cette etape qui publie l'echeancier definitif. Une echeance ne peut pas etre rendue
 * exigible sur un plan qui n'existe pas encore, et l'inverser reporterait d'une journee entiere la
 * premiere echeance de tout credit mobilise — un decalage invisible, qui ne se verrait qu'au
 * rapprochement des dates de valeur.
 *
 * <h2>Pourquoi l'etape est bloquante</h2>
 *
 * <p>Une mobilisation qui ne se clot pas laisse un credit sans echeancier : rien n'est reclame,
 * rien n'est en retard, rien n'est declasse. Le portefeuille parait sain et la comptabilite reste
 * equilibree — c'est exactement la forme de defaut que l'arrete est fait pour empecher.
 *
 * <p>Une tranche non tiree a la date limite, en revanche, n'est pas une anomalie : c'est un
 * chantier qui n'a pas avance, et il n'a pas a bloquer l'arrete de la banque.
 */
public final class LoanMobilisationStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final LoanMobilisationService service;

    public LoanMobilisationStep(LoanMobilisationService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "LOAN_MOBILISATION";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LoanMobilisationService.Outcome outcome = service.process(
            context.legalEntityId(), context.businessDate(), context.actorId(), context.runId());

        List<String> anomalies = new ArrayList<>(
            outcome.anomalies().stream().limit(MAX_REPORTED).toList());
        if (outcome.anomalies().size() > MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger les credits en mobilisation et relancer.");
        }
        return new StepResult(outcome.examined(), outcome.periodsBilled() + outcome.closed(),
                              anomalies);
    }
}
