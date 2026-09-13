package io.corebanking.interest.accrual;

import io.corebanking.interest.daycount.DayCount;
import io.corebanking.kernel.money.Money;
import io.corebanking.interest.rate.RateSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calcul des interets sur une serie de soldes en date de valeur.
 *
 * <p>Classe pure : aucune dependance a une base, a une horloge ou a un cadre technique. Elle est
 * donc integralement testable, et c'est voulu — c'est le composant dont une erreur se traduit
 * directement en reclamation client.
 *
 * <p><b>Regle d'arrondi.</b> Les montants sont accumules en precision interne sur toute la periode
 * et ne sont jamais arrondis en cours de route. L'arrondi n'intervient qu'a la capitalisation, une
 * seule fois. Arrondir chaque journee cree une derive systematique : sur un solde de
 * 1 200 000 XOF a 3,5 %, elle atteint 25 XOF par an et par compte.
 */
public final class InterestCalculator {

    private InterestCalculator() {}

    /**
     * Calcule les interets d'une periode.
     *
     * @param series serie contigue des soldes en date de valeur, un element par journee
     */
    public static PeriodAccrual accrue(List<DailyBalance> series, InterestBasis basis,
                                       AccrualSide side, RateSchedule rates, DayCount dayCount) {
        requireContiguous(series);
        return switch (basis) {
            case DAILY_BALANCE -> accrueDaily(series, side, rates, dayCount);
            case MINIMUM_BALANCE -> accrueOnFixedBasis(
                series, side, rates, dayCount, InterestBasis.MINIMUM_BALANCE,
                minimumBasis(series, side));
            case AVERAGE_DAILY_BALANCE -> accrueOnFixedBasis(
                series, side, rates, dayCount, InterestBasis.AVERAGE_DAILY_BALANCE,
                averageBasis(series, side));
        };
    }

    private static PeriodAccrual accrueDaily(List<DailyBalance> series, AccrualSide side,
                                             RateSchedule rates, DayCount dayCount) {
        List<DailyAccrual> days = new ArrayList<>(series.size());
        Money total = Money.zero(series.get(0).balance().currency());

        for (DailyBalance daily : series) {
            Money basis = side.basis(daily.balance());
            BigDecimal fraction = dayCount.dayFraction(daily.day());
            Money amount = rates.accrue(basis, fraction);
            days.add(new DailyAccrual(daily.day(), basis, rates.effectiveRate(basis),
                                      fraction, amount));
            total = total.plus(amount);
        }
        return new PeriodAccrual(InterestBasis.DAILY_BALANCE, side, days, total);
    }

    /**
     * Assiette unique appliquee a toute la periode : solde minimum ou solde moyen.
     *
     * <p>La fraction d'annee est celle de la periode entiere, calculee d'un seul tenant. La
     * decomposer jour par jour puis sommer donnerait un resultat different en 30/360, ou la somme
     * des fractions journalieres ne reconstitue pas la fraction de periode.
     */
    private static PeriodAccrual accrueOnFixedBasis(List<DailyBalance> series, AccrualSide side,
                                                    RateSchedule rates, DayCount dayCount,
                                                    InterestBasis basis, Money assiette) {
        LocalDate start = series.get(0).day();
        LocalDate endExclusive = series.get(series.size() - 1).day().plusDays(1);
        BigDecimal fraction = dayCount.yearFraction(start, endExclusive);
        Money total = rates.accrue(assiette, fraction);

        List<DailyAccrual> days = List.of(
            new DailyAccrual(start, assiette, rates.effectiveRate(assiette), fraction, total));
        return new PeriodAccrual(basis, side, days, total);
    }

    private static Money minimumBasis(List<DailyBalance> series, AccrualSide side) {
        return series.stream()
            .map(daily -> side.basis(daily.balance()))
            .min(Comparator.naturalOrder())
            .orElseThrow();
    }

    private static Money averageBasis(List<DailyBalance> series, AccrualSide side) {
        Money sum = Money.zero(series.get(0).balance().currency());
        for (DailyBalance daily : series) {
            sum = sum.plus(side.basis(daily.balance()));
        }
        return sum.dividedBy(new BigDecimal(series.size()));
    }

    /**
     * La serie doit couvrir chaque journee, sans trou ni doublon.
     *
     * <p>Un trou dans la serie fait disparaitre une journee d'interets sans qu'aucun controle
     * comptable ne le signale : l'ecriture produite reste equilibree, elle est simplement fausse.
     * C'est pourquoi le controle est ici, en entree du calcul, et non en aval.
     */
    private static void requireContiguous(List<DailyBalance> series) {
        if (series.isEmpty()) {
            throw new IllegalArgumentException("Serie de soldes vide : aucune journee a remunerer");
        }
        for (int i = 1; i < series.size(); i++) {
            LocalDate expected = series.get(i - 1).day().plusDays(1);
            if (!series.get(i).day().equals(expected)) {
                throw new IllegalArgumentException(
                    "Serie de soldes discontinue : " + expected + " attendu, "
                    + series.get(i).day() + " trouve. Une journee manquante est un interet perdu "
                    + "qu'aucun controle d'equilibre ne peut detecter.");
            }
        }
    }
}
