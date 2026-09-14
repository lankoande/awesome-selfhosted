package io.corebanking.tfj;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Contexte d'execution d'une etape.
 *
 * @param businessDate date comptable traitee. C'est elle, et jamais la date du jour, qui sert de
 *                     reference : un TFJ de rattrapage lance trois jours plus tard doit produire
 *                     exactement ce qu'il aurait produit le jour meme.
 */
public record TfjContext(
    UUID legalEntityId,
    LocalDate businessDate,
    UUID runId,
    UUID actorId,
    RunMode mode) {

    public boolean isDryRun() {
        return mode == RunMode.DRY_RUN;
    }
}
