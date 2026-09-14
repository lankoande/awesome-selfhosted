package io.corebanking.loan;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.MathContexts;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Interets intercalaires : ce que coute un credit pendant sa mobilisation.
 *
 * <h2>L'assiette est le montant mobilise, jour par jour</h2>
 *
 * <p>Tant que toutes les tranches ne sont pas debloquees, il n'y a pas de capital a amortir mais
 * il y a des fonds mis a disposition, et ils portent interet. L'assiette change a chaque
 * deblocage : elle vaut la somme des tranches deja versees, et pas une de plus.
 *
 * <p>C'est le point ou les contournements se paient. Faire courir les interets sur le montant
 * accorde fait payer a l'emprunteur des fonds qu'il n'a pas recus ; les faire courir seulement a
 * partir du dernier deblocage offre a l'emprunteur plusieurs mois de tresorerie gratuite. Les deux
 * erreurs laissent une comptabilite equilibree et ne se decouvrent qu'a la reclamation.
 *
 * <h2>Une tranche porte interet du jour de sa mise a disposition</h2>
 *
 * <p>Le jour du deblocage est compte, celui de la fin de periode aussi : la periode est fermee aux
 * deux bouts, exactement comme une periode d'echeance dans l'echeancier. C'est la meme convention
 * que celle du moteur d'interets courus, et c'est ce qui permet de les rapprocher.
 *
 * <h2>Arrondi</h2>
 *
 * <p>Les segments sont cumules a pleine precision et l'arrondi n'intervient qu'une fois, sur le
 * total de la periode. Arrondir segment par segment ferait dependre l'interet du nombre de
 * deblocages intervenus dans la periode — deux tranches au lieu d'une changeraient le montant du
 * dernier franc, sans qu'aucun controle ne puisse dire lequel des deux est le bon.
 */
public final class InterimInterest {

    private InterimInterest() {}

    /** Une mise a disposition : un montant, une date de valeur. */
    public record Drawing(LocalDate on, Money amount) {

        public Drawing {
            Objects.requireNonNull(on, "on");
            Objects.requireNonNull(amount, "amount");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException(
                    "Mise a disposition de " + amount + " : un deblocage nul n'en est pas un.");
            }
        }
    }

    /** Interets d'une periode intercalaire, et l'assiette qui les a produits. */
    public record Accrual(LocalDate from, LocalDate toInclusive, Money drawnAtEnd, Money interest) {}

    /**
     * Interets intercalaires d'une periode, bornes incluses.
     *
     * @param drawings mises a disposition du contrat, dans n'importe quel ordre ; celles qui
     *                 suivent la fin de periode sont ignorees, celles qui la precedent comptent
     *                 pour toute la periode
     */
    public static Accrual accrue(List<Drawing> drawings, LocalDate from, LocalDate toInclusive,
                                 BigDecimal annualRatePercent, DayCountConvention dayCount,
                                 CurrencyRef currency) {
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(currency, "currency");
        if (toInclusive.isBefore(from)) {
            throw new IllegalArgumentException(
                "Periode intercalaire du " + from + " au " + toInclusive + " : elle se termine "
                + "avant de commencer.");
        }
        BigDecimal rate = annualRatePercent == null ? BigDecimal.ZERO
                                                    : annualRatePercent.movePointLeft(2);
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("Taux intercalaire negatif : " + annualRatePercent);
        }

        List<LocalDate> boundaries = boundaries(drawings, from, toInclusive);
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < boundaries.size() - 1; index++) {
            LocalDate start = boundaries.get(index);
            LocalDate endExclusive = boundaries.get(index + 1);
            Money basis = drawnAt(drawings, start, currency);
            if (basis.isPositive()) {
                total = total.add(basis.amount()
                    .multiply(rate, MathContexts.RATE)
                    .multiply(dayCount.yearFraction(start, endExclusive), MathContexts.RATE));
            }
        }
        Money precise = Money.of(
            total.setScale(Money.MAX_INTERNAL_SCALE, java.math.RoundingMode.HALF_EVEN), currency);
        return new Accrual(from, toInclusive, drawnAt(drawings, toInclusive, currency),
                           precise.roundToCurrency());
    }

    /**
     * Bornes des segments a assiette constante : le debut de periode, chaque deblocage intervenu
     * dans la periode, et le lendemain de la fin — la borne haute des conventions de decompte
     * etant exclue.
     */
    private static List<LocalDate> boundaries(List<Drawing> drawings, LocalDate from,
                                              LocalDate toInclusive) {
        java.util.TreeSet<LocalDate> dates = new java.util.TreeSet<>();
        dates.add(from);
        dates.add(toInclusive.plusDays(1));
        for (Drawing drawing : drawings) {
            if (drawing.on().isAfter(from) && !drawing.on().isAfter(toInclusive)) {
                dates.add(drawing.on());
            }
        }
        return new ArrayList<>(dates);
    }

    /** Montant mobilise a une date, bornes incluses. */
    public static Money drawnAt(List<Drawing> drawings, LocalDate at, CurrencyRef currency) {
        Money total = Money.zero(currency);
        for (Drawing drawing : drawings) {
            if (!drawing.on().isAfter(at)) {
                total = total.plus(drawing.amount());
            }
        }
        return total;
    }

    /**
     * Fins de periodes intercalaires, calees a rebours sur la premiere echeance d'amortissement.
     *
     * <p>Elles ne se comptent pas depuis le deblocage mais <b>depuis la premiere echeance</b>, en
     * remontant. Le client paie ses interets intercalaires le jour du mois ou il paiera ensuite
     * ses echeances, et le passage de la mobilisation a l'amortissement ne produit pas une periode
     * batarde a cheval sur deux calendriers.
     *
     * @param through derniere date a considerer, incluse
     * @return dates ordonnees, strictement posterieures au deblocage et strictement anterieures a
     *         la premiere echeance
     */
    public static List<LocalDate> periodEnds(LoanTerms terms, LocalDate through) {
        Periodicity frequency = terms.frequency();
        List<LocalDate> ends = new ArrayList<>();
        LocalDate limit = through.isBefore(terms.firstDueDate()) ? through
                                                                 : terms.firstDueDate().minusDays(1);
        for (int back = 1; ; back++) {
            LocalDate end = frequency.beforeAnchor(terms.firstDueDate(), back);
            if (!end.isAfter(terms.disbursedOn())) {
                break;
            }
            if (!end.isAfter(limit)) {
                ends.add(end);
            }
        }
        ends.sort(LocalDate::compareTo);
        return List.copyOf(ends);
    }
}
