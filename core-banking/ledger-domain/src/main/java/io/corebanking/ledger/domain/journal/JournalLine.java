package io.corebanking.ledger.domain.journal;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Ligne d'ecriture enregistree. Immuable, comme l'ecriture qui la porte. */
public record JournalLine(
    UUID id,
    UUID entryId,
    UUID legalEntityId,
    LocalDate bookingDate,
    int lineNumber,
    UUID accountId,
    Direction direction,
    Money amount,
    Money functionalAmount,
    BigDecimal fxRate,
    LocalDate valueDate,
    int stripeId,
    String label,
    Instant knowledgeTime) {}
