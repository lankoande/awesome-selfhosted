package io.corebanking.tfj.steps;

import io.corebanking.deposits.StandingOrderService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ordres permanents a l'echeance.
 *
 * <p>L'etape vient apres les prelevements : un prelevement est l'engagement du client envers un
 * creancier, dont le rejet lui est opposable ; un ordre permanent est son propre ordre, qu'il peut
 * revoquer. Quand la provision ne suffit pas aux deux, c'est celui qu'il a donne qui cede.
 *
 * <p>Un rejet — provision, plafond, compte bloque — n'est pas une anomalie : il est enregistre, et
 * l'echeance se retente le jour ouvre suivant, un nombre borne de fois. Ce qui fait anomalie, et
 * arrete la journee, est un defaut technique ou de parametrage. L'annulation de l'arrete
 * contre-passe les ecritures et rend les ordres a leur echeance, sans tentative consommee.
 */
public final class StandingOrdersStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;
    private final StandingOrderService standingOrders;

    public StandingOrdersStep(Database database, StandingOrderService standingOrders) {
        this.database = database;
        this.standingOrders = standingOrders;
    }

    @Override
    public String name() {
        return "STANDING_ORDERS";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> due = database.inTransaction(
            c -> StandingOrderService.due(c, context.legalEntityId(), context.businessDate()));
        if (due.isEmpty()) {
            return StepResult.none();
        }
        List<String> anomalies = new ArrayList<>();
        // Ce qui est compte comme ecrit est ce que l'etape a traite — vire, rejete ou passe sans
        // objet —, comme pour les prelevements : chacun laisse sa trace, et un rejet est un
        // resultat. Seule une echeance qui n'etait pas a traiter ne compte pas.
        long processed = 0;
        for (UUID id : due) {
            try {
                StandingOrderService.Execution execution = standingOrders.execute(
                    id, context.runId(), context.actorId());
                if (execution != null) {
                    processed++;
                }
            } catch (RuntimeException e) {
                if (anomalies.size() < MAX_REPORTED) {
                    anomalies.add("ordre permanent " + id + " : " + e.getMessage());
                } else if (anomalies.size() == MAX_REPORTED) {
                    anomalies.add("... liste tronquee ; corriger et relancer.");
                }
            }
        }
        return new StepResult(due.size(), processed, anomalies);
    }
}
