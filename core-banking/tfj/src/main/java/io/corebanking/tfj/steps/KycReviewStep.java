package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.party.KycReviews;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Revue periodique de la connaissance client : les dossiers dont l'echeance de revue est depassee
 * passent en revue expiree. Leurs comptes continuent de fonctionner ; rien de nouveau ne s'y
 * ouvre. Les references expirees sont rendues en anomalies non bloquantes : c'est la liste de
 * travail du lendemain, pas un motif d'arreter la banque.
 */
public final class KycReviewStep implements TfjStep {

    private static final int MAX_REPORTED = 50;

    private final Database database;

    public KycReviewStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "KYC_REVIEW";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> expired = database.inTransaction(c -> KycReviews.expire(
            c, context.legalEntityId(), context.businessDate(), context.runId(),
            context.actorId()));
        List<String> reported = expired.stream().limit(MAX_REPORTED)
            .map(reference -> "revue de connaissance client a refaire : " + reference).toList();
        if (expired.size() > MAX_REPORTED) {
            reported = new java.util.ArrayList<>(reported);
            reported.add("... " + (expired.size() - MAX_REPORTED) + " autres dossiers");
        }
        return new StepResult(expired.size(), expired.size(), reported);
    }
}
