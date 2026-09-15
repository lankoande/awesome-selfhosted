package io.corebanking.ledger.domain.journal;

import io.corebanking.ledger.domain.posting.PostingSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Ecriture enregistree. Immuable : aucune mise a jour, aucune suppression.
 *
 * <p>Une erreur se corrige par contre-passation, jamais par modification. L'immuabilite est
 * garantie a trois niveaux — le domaine n'expose aucun mutateur, la base refuse
 * {@code UPDATE}/{@code DELETE} par declencheur, et l'utilisateur applicatif n'a pas ces droits.
 *
 * @param bookingDate   date comptable : rattachement a l'exercice et aux arretes.
 * @param knowledgeTime instant ou l'ecriture est entree dans le systeme. C'est le second axe
 *                      temporel du ledger : il permet de repondre a « quel etait le solde au
 *                      31/12 <b>tel qu'on le connaissait</b> au 15/01 », question a laquelle un
 *                      ledger mono-temporel ne sait pas repondre — il ne peut que constater que
 *                      les chiffres ont change.
 */
public record JournalEntry(
    UUID id,
    UUID legalEntityId,
    long entryNumber,
    LocalDate bookingDate,
    String transactionType,
    PostingSource source,
    UUID batchRunId,
    UUID reversalOf,
    String idempotencyKey,
    String narrative,
    Map<String, String> metadata,
    UUID createdBy,
    Instant knowledgeTime,
    UUID branchId) {

    public boolean isReversal() {
        return reversalOf != null;
    }
}
