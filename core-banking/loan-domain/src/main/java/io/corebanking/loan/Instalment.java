package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Une echeance de l'echeancier.
 *
 * <p>Les composantes sont conservees separement et <b>chacune est imputable telle quelle</b>. Le
 * total n'est jamais arrondi pour lui-meme : il est la somme des composantes. Arrondir le total
 * puis en deduire une composante par difference donnerait un echeancier dont les colonnes ne
 * s'additionnent pas — et une taxe declaree sans rapport avec l'interet reellement porte au compte
 * de produit.
 *
 * @param periodStart premier jour d'interet de l'echeance, inclus
 * @param periodEnd   dernier jour d'interet de l'echeance, inclus ; c'est la date d'echeance
 */
public record Instalment(
    int number,
    LocalDate dueDate,
    LocalDate periodStart,
    LocalDate periodEnd,
    Money outstandingBefore,
    Money principal,
    Money interest,
    Money insurance,
    Money fee,
    Money tax,
    Money total,
    Money outstandingAfter) {

    public Instalment {
        Objects.requireNonNull(dueDate, "dueDate");
        if (number < 1) {
            throw new IllegalArgumentException("Rang d'echeance invalide : " + number);
        }
        requireBookable(principal, number, "le capital");
        requireBookable(interest, number, "l'interet");
        requireBookable(insurance, number, "l'assurance");
        requireBookable(fee, number, "les frais");
        requireBookable(tax, number, "la taxe");

        Money parts = principal.plus(interest).plus(insurance).plus(fee).plus(tax);
        if (!total.equals(parts)) {
            throw new IllegalArgumentException(
                "Echeance " + number + " : total " + total + " different de la somme de ses "
                + "composantes " + parts + ". Le total d'une echeance n'est jamais arrondi pour "
                + "lui-meme.");
        }
        if (!outstandingAfter.equals(outstandingBefore.minus(principal))) {
            throw new IllegalArgumentException(
                "Echeance " + number + " : capital restant du incoherent — " + outstandingBefore
                + " moins " + principal + " ne donne pas " + outstandingAfter + ".");
        }
        if (outstandingAfter.isNegative()) {
            throw new IllegalArgumentException(
                "Echeance " + number + " : le capital restant du devient negatif (" + outstandingAfter
                + "). L'echeancier rembourserait plus que le capital emprunte.");
        }
    }

    /** Part de l'echeance exigible au titre de la dette, hors capital. */
    public Money charges() {
        return interest.plus(insurance).plus(fee).plus(tax);
    }

    private static void requireBookable(Money amount, int number, String what) {
        Objects.requireNonNull(amount, what);
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                "Echeance " + number + " : " + what + " est negatif (" + amount + ").");
        }
        if (!amount.isBookable()) {
            throw new IllegalArgumentException(
                "Echeance " + number + " : " + what + " " + amount + " n'est pas imputable en "
                + amount.currency() + ". Une echeance se regle, elle ne s'arrondit pas au guichet.");
        }
    }
}
