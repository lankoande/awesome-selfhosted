package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.FxRevaluation;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Revalorisation des positions de change.
 *
 * <p>Chaque position est confrontee au cours du jour et l'ecart va au resultat de change. L'etape
 * vient apres tous les traitements comptables : elle revalorise ce que la journee a laisse, et
 * une ecriture posterieure a la revalorisation vaudrait a son cours propre, pas au cours de
 * cloture. Elle vient avant le cliche des soldes, qui doit refleter la journee arretee,
 * revalorisation comprise.
 *
 * <p>Bloquante : un ecart de change non comptabilise ne laisse aucune trace — le bilan reste
 * equilibre, les controles passent, et le resultat est faux du montant de l'ecart.
 */
public final class FxRevaluationStep implements TfjStep {

    private final FxRevaluation revaluation;

    public FxRevaluationStep(FxRevaluation revaluation) {
        this.revaluation = revaluation;
    }

    @Override
    public String name() {
        return FxRevaluation.STEP;
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<FxRevaluation.Result> results = revaluation.revalue(
            context.legalEntityId(), context.businessDate(), context.runId(), context.actorId());
        long posted = results.stream().filter(r -> r.entryId() != null).count();
        return StepResult.of(results.size(), posted);
    }
}
