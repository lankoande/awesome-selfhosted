package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Resultat d'une comptabilisation.
 *
 * @param replayed vrai si la commande avait deja ete traitee : la cle d'idempotence existait, et
 *                 le resultat renvoye est celui de l'execution initiale. Aucune seconde ecriture
 *                 n'a ete produite.
 */
public record PostingResult(
    UUID entryId,
    long entryNumber,
    LocalDate bookingDate,
    Instant knowledgeTime,
    boolean replayed,
    Map<UUID, Money> balancesAfter) {}
