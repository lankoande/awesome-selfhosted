package io.corebanking.tfj.steps;

import io.corebanking.compliance.MonitoringService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;

/**
 * Surveillance LCB-FT de la journee arretee.
 *
 * <p>L'etape vient avec les revues — dormance, connaissance client, suspens — et pour la meme
 * raison : <b>elle ne comptabilise rien et ne bloque pas la journee</b>. Un client suspect n'est
 * pas une panne de la banque, et une alerte qui empecherait l'arrete ferait de la conformite le
 * premier obstacle a la comptabilite. Elle rend une file de travail pour le lendemain.
 *
 * <p>Elle vient <b>apres les traitements comptables</b> : ce qu'elle regarde est la journee telle
 * qu'elle a ete arretee, prelevements et echeances compris. Un scenario qui tournerait avant
 * ignorerait la moitie des mouvements du jour.
 *
 * <p>Ce qui fait anomalie ici n'est pas une alerte — c'est un scenario qui ne s'execute pas : un
 * defaut de parametrage, que personne ne verrait autrement avant l'inspection.
 */
public final class AmlMonitoringStep implements TfjStep {

    private final MonitoringService monitoring;

    public AmlMonitoringStep(MonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @Override
    public String name() {
        return "AML_MONITORING";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        MonitoringService.Result result = monitoring.run(context.legalEntityId(),
                                                         context.businessDate(), context.runId(),
                                                         context.actorId());
        return new StepResult(result.scenarios(), result.alerts(), result.anomalies());
    }
}
