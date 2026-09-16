package io.corebanking.tfj.steps;

import io.corebanking.deposits.DirectDebitService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Prelevements a l'echeance.
 *
 * <p>Tout prelevement en attente dont l'echeance est arrivee s'execute : le debiteur est debite,
 * ou le creancier credite sauf bonne fin, ou le prelevement est rejete pour un motif nomme. Un
 * rejet — provision, mandat revoque, compte qui ne peut pas operer — n'est pas une anomalie : il
 * est enregistre, et le creancier en est informe. Ce qui fait anomalie, et arrete la journee,
 * est un defaut technique ou de parametrage : une condition de date de valeur absente, un compte
 * de reglement inconnu. L'etape vient apres l'expiration des blocages et avant les commissions et
 * les echeances de credit de la banque : le prelevement est un engagement du client envers un
 * tiers, pris a date, et son rejet lui est opposable ; commission et echeance ont leur regime.
 *
 * <p>Chaque execution tient dans sa transaction et porte le traitement : l'annulation de
 * l'arrete contre-passe ses ecritures et rend les prelevements a l'attente.
 */
public final class DirectDebitsStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;
    private final DirectDebitService directDebits;

    public DirectDebitsStep(Database database, DirectDebitService directDebits) {
        this.database = database;
        this.directDebits = directDebits;
    }

    @Override
    public String name() {
        return DirectDebitService.STEP;
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> due = database.inTransaction(
            c -> DirectDebitService.due(c, context.legalEntityId(), context.businessDate()));
        if (due.isEmpty()) {
            return StepResult.none();
        }
        List<String> anomalies = new ArrayList<>();
        long executed = 0;
        for (UUID id : due) {
            try {
                DirectDebitService.DirectDebit dd = directDebits.execute(id, context.runId(),
                                                                         context.actorId());
                if (!"PENDING".equals(dd.status())) {
                    executed++;
                }
            } catch (RuntimeException e) {
                if (anomalies.size() < MAX_REPORTED) {
                    anomalies.add("prelevement " + id + " : " + e.getMessage());
                } else if (anomalies.size() == MAX_REPORTED) {
                    anomalies.add("... liste tronquee ; corriger et relancer.");
                }
            }
        }
        return new StepResult(due.size(), executed, anomalies);
    }
}
