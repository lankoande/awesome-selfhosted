package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reconstruction de l'echeancier apres un remboursement anticipe partiel.
 *
 * <h2>Les deux options, et ce qu'elles coutent</h2>
 *
 * <p>A capital egal rembourse par anticipation, <b>reduire la duree</b> fait economiser bien plus
 * d'interets que <b>reduire l'echeance</b> : dans le premier cas le capital s'amortit au meme
 * rythme qu'avant et les interets cessent plus tot ; dans le second, le capital restant court sur
 * toute la duree initiale. Le choix appartient a l'emprunteur, et ne proposer que l'un des deux est
 * un defaut fonctionnel, pas une simplification.
 *
 * <h2>Ce que la reconstruction ne change pas</h2>
 *
 * <p>Le taux, la convention de jours, l'assurance et les frais restent ceux du contrat. Les
 * reviser au passage transformerait un remboursement anticipe — un droit de l'emprunteur — en
 * renegociation, qui est une autre operation et demande un autre consentement.
 */
public final class Prepayments {

    private Prepayments() {}

    /**
     * Echeancier des echeances a venir, apres imputation du remboursement sur le capital.
     *
     * @param remaining      capital restant du <b>apres</b> le remboursement anticipe
     * @param nextDueDate    date de la premiere echeance a venir
     * @param remainingCount nombre d'echeances qu'il restait avant le remboursement
     * @param currentAnnuity part capital et interet de l'echeance en cours, cible du maintien
     * @return vide lorsque le remboursement solde le credit
     */
    public static Optional<AmortisationSchedule> rebuild(LoanTerms current, Money remaining,
                                                         LocalDate on, LocalDate nextDueDate,
                                                         int remainingCount, PrepaymentMode mode,
                                                         Money currentAnnuity) {
        if (remaining.isNegative()) {
            throw new IllegalArgumentException(
                "Capital restant negatif apres remboursement : " + remaining
                + ". Un remboursement superieur au capital restant du solde le credit, il ne le "
                + "rend pas crediteur.");
        }
        if (remaining.isZero()) {
            return Optional.empty();
        }
        if (remainingCount <= 0) {
            throw new IllegalArgumentException(
                "Aucune echeance a venir alors qu'il reste " + remaining + " a amortir.");
        }
        int count = switch (mode) {
            case REDUCE_INSTALMENT -> remainingCount;
            case SHORTEN_TERM -> shortestTerm(current, remaining, remainingCount, currentAnnuity);
        };
        return Optional.of(ScheduleGenerator.generate(
            current.forRemaining(remaining, count, on, nextDueDate)));
    }

    /**
     * Plus courte duree au bout de laquelle l'echeance du contrat suffit encore a amortir.
     *
     * <p>L'annuite decroit quand la duree s'allonge : la plus courte duree admissible est donc la
     * premiere pour laquelle l'annuite recalculee ne depasse pas celle du contrat. La recherche est
     * bornee par le nombre d'echeances restantes — au-dela, l'emprunteur paierait plus longtemps
     * qu'avant d'avoir rembourse par anticipation, ce qui n'aurait aucun sens.
     */
    private static int shortestTerm(LoanTerms current, Money remaining, int remainingCount,
                                    Money currentAnnuity) {
        return switch (current.method()) {
            case CONSTANT_ANNUITY -> {
                BigDecimal rate = current.periodicRate();
                for (int count = 1; count < remainingCount; count++) {
                    if (!ScheduleGenerator.annuity(remaining, rate, count)
                             .isGreaterThan(currentAnnuity)) {
                        yield count;
                    }
                }
                yield remainingCount;
            }
            case CONSTANT_PRINCIPAL -> {
                // La part de capital ne bouge pas : la duree se deduit du capital restant.
                Money perInstalment = currentAnnuity;
                int count = remaining.amount()
                    .divide(perInstalment.amount(), 0, java.math.RoundingMode.CEILING)
                    .intValueExact();
                yield Math.max(1, Math.min(count, remainingCount));
            }
            // In fine : il n'y a rien a raccourcir, le capital est du a la derniere echeance.
            case BULLET -> remainingCount;
        };
    }
}
