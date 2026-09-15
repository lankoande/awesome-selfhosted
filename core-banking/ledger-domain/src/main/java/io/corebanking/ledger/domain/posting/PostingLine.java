package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Ligne d'une commande de comptabilisation.
 *
 * @param valueDate date de valeur, portee <b>par la ligne</b> et non par l'ecriture. Un virement
 *                  interbancaire debite le client en date de valeur J et credite le compte de
 *                  liaison en J+2 : les deux lignes de la meme ecriture portent des dates de
 *                  valeur differentes. Un modele ou la date de valeur appartient a l'ecriture rend
 *                  ce cas — courant — impossible a representer correctement.
 * @param fxRate    cours applique pour convertir vers la devise de tenue de compte de l'entite.
 *                  Nul si la ligne est deja dans cette devise. Archive sur la ligne : la
 *                  conversion reste explicable meme si le cours est corrige par la suite.
 */
public record PostingLine(
    UUID accountId,
    Direction direction,
    Money amount,
    LocalDate valueDate,
    String label,
    BigDecimal fxRate,
    UUID branchId) {

    public PostingLine(UUID accountId, Direction direction, Money amount, LocalDate valueDate,
                       String label, BigDecimal fxRate) {
        this(accountId, direction, amount, valueDate, label, fxRate, null);
    }

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(valueDate, "valueDate");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                "Montant de ligne non strictement positif : " + amount
                + ". Le sens est porte par la direction, jamais par le signe.");
        }
        if (fxRate != null && fxRate.signum() <= 0) {
            throw new IllegalArgumentException("Cours de change non strictement positif : " + fxRate);
        }
    }

    public static PostingLine debit(UUID accountId, Money amount, LocalDate valueDate, String label) {
        return new PostingLine(accountId, Direction.DEBIT, amount, valueDate, label, null);
    }

    public static PostingLine credit(UUID accountId, Money amount, LocalDate valueDate, String label) {
        return new PostingLine(accountId, Direction.CREDIT, amount, valueDate, label, null);
    }

    public PostingLine withFxRate(BigDecimal rate) {
        return new PostingLine(accountId, direction, amount, valueDate, label, rate, branchId);
    }

    /**
     * Agence comptable de la ligne, pour un compte general. Sur un compte client ou interne,
     * l'agence est celle du compte et une valeur contraire est refusee.
     */
    public PostingLine withBranch(UUID branch) {
        return new PostingLine(accountId, direction, amount, valueDate, label, fxRate, branch);
    }

    /** Ligne inverse, memes montant et date de valeur. Utilisee par la contre-passation. */
    public PostingLine reversed() {
        return new PostingLine(accountId, direction.opposite(), amount, valueDate,
                               label == null ? null : "Extourne : " + label, fxRate, branchId);
    }
}
