package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.journal.JournalEntry;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Unique porte d'entree en ecriture du ledger.
 *
 * <p>Le contrat est volontairement etroit : on ajoute une ecriture, ou on en annule une par une
 * ecriture inverse. Il n'existe ni mise a jour, ni suppression, ni forcage de solde.
 */
public interface PostingService {

    /**
     * Comptabilise une commande de facon atomique et idempotente.
     *
     * <p>Un rejeu — reprise de TFJ, retry reseau, double soumission, redelivery d'un message —
     * renvoie le resultat initial sans produire de seconde ecriture.
     *
     * @throws io.corebanking.ledger.domain.error.LedgerViolation si un invariant est viole ;
     *         aucune ecriture, aucun solde, aucun etat intermediaire n'est alors persiste.
     */
    PostingResult post(PostingCommand command);

    /**
     * Contre-passe integralement une ecriture : produit une ecriture inverse liee a l'originale,
     * dates de valeur reprises a l'identique. L'originale n'est jamais modifiee.
     *
     * <p>Une ecriture ne peut etre contre-passee qu'une fois.
     */
    PostingResult reverse(UUID entryId, LocalDate bookingDate, LocalDate reversalBookingDate,
                          IdempotencyKey key, String reason);

    /** Relecture d'une ecriture, pour audit et contre-passation. */
    JournalEntry findEntry(UUID entryId, LocalDate bookingDate);
}
