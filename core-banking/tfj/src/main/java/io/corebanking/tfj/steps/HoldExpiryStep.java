package io.corebanking.tfj.steps;

import io.corebanking.deposits.Holds;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;

/**
 * Expiration des blocages de montant.
 *
 * <p>Un blocage expire par date comptable, pas par horloge : c'est la journee arretee qui le leve,
 * et l'annulation de l'arrete le repose. L'etape vient avant tout prelevement, parce que les
 * commissions et les echeances se prelevent sur le disponible de la journee arretee, blocages
 * expires compris.
 */
public final class HoldExpiryStep implements TfjStep {

    private final Database database;

    public HoldExpiryStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "HOLD_EXPIRY";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        int released = database.inTransaction(c -> Holds.expire(
            c, context.legalEntityId(), context.businessDate(), context.runId(),
            context.actorId()));
        return StepResult.of(released, released);
    }
}
