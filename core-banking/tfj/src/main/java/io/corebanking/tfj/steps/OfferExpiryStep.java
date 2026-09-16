package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.loan.service.LoanOrigination;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Offres de credit perimees.
 *
 * <p>Un accord donne sur une situation ancienne n'est plus un accord : les revenus ont change, les
 * engagements aussi, et le taux propose n'est peut-etre plus finançable. Passe sa validite, l'offre
 * s'eteint et le dossier se reinstruit. L'etape ne comptabilise rien — aucun engagement n'etait
 * porte — et ne bloque pas la journee ; l'annulation de l'arrete rend les offres a l'accord.
 */
public final class OfferExpiryStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;

    public OfferExpiryStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "OFFER_EXPIRY";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> expired = database.inTransaction(c -> LoanOrigination.expire(
            c, context.legalEntityId(), context.businessDate(), context.runId(),
            context.actorId()));
        List<String> anomalies = new java.util.ArrayList<>(
            expired.stream().limit(MAX_REPORTED).map(r -> "offre expiree : " + r).toList());
        if (expired.size() > MAX_REPORTED) {
            anomalies.add("... " + (expired.size() - MAX_REPORTED) + " autre(s) offre(s) expiree(s).");
        }
        return new StepResult(expired.size(), expired.size(), anomalies);
    }
}
