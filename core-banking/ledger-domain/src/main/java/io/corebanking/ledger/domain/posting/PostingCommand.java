package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.id.IdempotencyKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Commande de comptabilisation : l'unique porte d'entree du ledger.
 *
 * <p>Aucun module metier n'ecrit directement dans le journal. Il publie un evenement metier que le
 * schema comptable du produit traduit en commande. La logique comptable reste ainsi centralisee et
 * parametree : ajouter un produit ne touche ni au ledger, ni a la comptabilite generale.
 */
public record PostingCommand(
    IdempotencyKey idempotencyKey,
    UUID legalEntityId,
    LocalDate bookingDate,
    String transactionType,
    UUID actorId,
    PostingSource source,
    UUID batchRunId,
    List<PostingLine> lines,
    Map<String, String> metadata) {

    public PostingCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(bookingDate, "bookingDate");
        Objects.requireNonNull(transactionType, "transactionType");
        Objects.requireNonNull(actorId,
            "actorId : toute ecriture est imputable a une identite nominative, y compris "
            + "lorsqu'elle est produite par un automate.");
        Objects.requireNonNull(source, "source");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if (source == PostingSource.BATCH && batchRunId == null) {
            throw new IllegalArgumentException(
                "Une ecriture de source BATCH doit porter son batchRunId : c'est ce qui rend "
                + "l'annulation integrale d'un TFJ possible.");
        }
    }

    public static PostingCommand online(IdempotencyKey key, UUID legalEntityId, LocalDate bookingDate,
                                        String transactionType, UUID actorId,
                                        List<PostingLine> lines) {
        return new PostingCommand(key, legalEntityId, bookingDate, transactionType, actorId,
                                  PostingSource.ONLINE, null, lines, Map.of());
    }

    public static PostingCommand batch(IdempotencyKey key, UUID legalEntityId, LocalDate bookingDate,
                                       String transactionType, UUID actorId, UUID batchRunId,
                                       List<PostingLine> lines) {
        return new PostingCommand(key, legalEntityId, bookingDate, transactionType, actorId,
                                  PostingSource.BATCH, batchRunId, lines, Map.of());
    }

    public PostingCommand withMetadata(Map<String, String> extra) {
        return new PostingCommand(idempotencyKey, legalEntityId, bookingDate, transactionType,
                                  actorId, source, batchRunId, lines, extra);
    }
}
