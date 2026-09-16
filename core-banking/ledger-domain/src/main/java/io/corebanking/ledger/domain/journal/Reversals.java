package io.corebanking.ledger.domain.journal;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Construction d'une contre-passation. */
public final class Reversals {

    private Reversals() {}

    /**
     * Ecriture inverse d'une ecriture existante.
     *
     * <p>Deux regles sont structurantes :
     *
     * <ul>
     *   <li>les <b>dates de valeur sont reprises a l'identique</b>. Les decaler rendrait faux tous
     *       les interets deja calcules sur la periode ;</li>
     *   <li>la <b>date comptable est celle du jour</b> de la correction, sauf si la periode
     *       d'origine est encore ouverte et que la politique de l'entite autorise le rattachement
     *       a la periode initiale.</li>
     * </ul>
     *
     * <p>Une contre-passation ne se contre-passe pas : on reemet l'ecriture correcte.
     */
    public static PostingCommand reverse(JournalEntry original, List<PostingLine> originalLines,
                                         LocalDate reversalBookingDate, IdempotencyKey key,
                                         UUID actorId, String reason) {
        if (original.isReversal()) {
            throw new InvalidPostingException(
                "L'ecriture " + original.id() + " est deja une contre-passation. "
                + "Corriger en reemettant l'ecriture juste, pas en contre-passant l'extourne.");
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidPostingException("Motif de contre-passation obligatoire.");
        }
        List<PostingLine> reversedLines = originalLines.stream().map(PostingLine::reversed).toList();

        return new PostingCommand(
            key,
            original.legalEntityId(),
            reversalBookingDate,
            original.transactionType(),   // repris : une famille d'ecritures s'exclut d'un etat avec ses contre-passations

            actorId,
            PostingSource.CORRECTION,
            null,
            reversedLines,
            Map.of("reversal_of", original.id().toString(), "reason", reason),
            original.branchId());
    }
}
