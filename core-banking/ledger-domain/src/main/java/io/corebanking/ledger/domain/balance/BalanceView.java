package io.corebanking.ledger.domain.balance;

import io.corebanking.kernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Solde d'un compte, sur les deux axes temporels du ledger.
 *
 * @param asOfBookingDate date comptable de reference : « le solde <b>au</b> ... »
 * @param asKnownAt       instant de connaissance : « ... <b>tel que connu au</b> ... »
 *
 * <p>La seconde dimension est ce qui permet d'expliquer qu'un etat produit en janvier et le meme
 * etat regenere en mars different : entre les deux, des ecritures antidatees sont arrivees. Un
 * ledger mono-temporel ne peut que constater l'ecart sans le justifier — ce qui est precisement
 * la question posee en inspection.
 */
public record BalanceView(
    UUID accountId,
    Money balance,
    LocalDate asOfBookingDate,
    Instant asKnownAt) {}
