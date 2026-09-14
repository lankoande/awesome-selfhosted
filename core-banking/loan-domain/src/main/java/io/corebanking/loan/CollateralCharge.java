package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Surete grevant un actif au profit d'un credit.
 *
 * <p>La distinction qui compte : {@code assetValue} est ce que <b>vaut l'actif</b>,
 * {@code securedAmount} est ce que <b>la surete garantit</b>. Une hypotheque de premier rang de
 * 10 M sur un immeuble qui en vaut 30 ne couvre que 10 ; inversement, une hypotheque de 30 sur un
 * immeuble qui en vaut 10 ne couvre que 10. Confondre les deux surevalue la couverture dans un cas
 * sur deux.
 *
 * @param rank         rang de la surete sur l'actif. Un second rang n'est couvert que par ce que
 *                     le premier laisse.
 * @param sharePercent quote-part affectee a ce credit. Une meme hypotheque peut garantir plusieurs
 *                     credits ; la compter en entier sur chacun diviserait la provision du client
 *                     par le nombre de ses credits.
 */
public record CollateralCharge(
    UUID id,
    String assetReference,
    String kind,
    Money assetValue,
    Money securedAmount,
    int rank,
    LocalDate valuedOn,
    BigDecimal sharePercent) {

    public CollateralCharge {
        Objects.requireNonNull(assetReference, "assetReference");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(assetValue, "assetValue");
        Objects.requireNonNull(securedAmount, "securedAmount");
        Objects.requireNonNull(sharePercent, "sharePercent");
        if (assetValue.isNegative() || securedAmount.isNegative()) {
            throw new IllegalArgumentException("Valeur ou montant garanti negatif sur " + kind);
        }
        if (rank < 1) {
            throw new IllegalArgumentException("Rang de surete invalide : " + rank);
        }
        if (sharePercent.signum() <= 0
            || sharePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(
                "Quote-part de " + sharePercent + " % hors de ]0..100].");
        }
    }
}
