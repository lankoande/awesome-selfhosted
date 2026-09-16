package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.party.PartyDocuments;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Pieces du dossier arrivees a expiration.
 *
 * <p>Une piece qui expire est constatee, une fois, et le dossier en porte la trace. Elle ne
 * bloque rien ce soir-la : le compte continue de fonctionner, mais plus rien ne s'ouvrira sur ce
 * dossier tant que la piece n'est pas renouvelee — la restriction est progressive, et elle
 * s'annonce. L'etape ne comptabilise rien, ne bloque pas la journee, et l'annulation de l'arrete
 * efface ses constats.
 */
public final class DocumentExpiryStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;

    public DocumentExpiryStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "DOCUMENT_EXPIRY";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> expired = database.inTransaction(c -> PartyDocuments.expire(
            c, context.legalEntityId(), context.businessDate(), context.runId(),
            context.actorId()));
        List<String> anomalies = new java.util.ArrayList<>(
            expired.stream().limit(MAX_REPORTED).toList());
        if (expired.size() > MAX_REPORTED) {
            anomalies.add("... " + (expired.size() - MAX_REPORTED) + " autre(s) piece(s) expiree(s).");
        }
        return new StepResult(expired.size(), expired.size(), anomalies);
    }
}
