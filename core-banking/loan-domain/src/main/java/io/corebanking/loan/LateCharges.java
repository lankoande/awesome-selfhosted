package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Calcul des interets de retard et des penalites.
 *
 * <p>Fonction pure : l'assiette lui est fournie constatee, jour par jour. C'est ce qui permet de
 * verifier le calcul sur des cas ecrits a la main — un moteur de retard mele a ses lectures ne se
 * teste qu'a travers un jeu de donnees, et le jeu de donnees devient vite plus difficile a relire
 * que le calcul lui-meme.
 */
public final class LateCharges {

    private LateCharges() {}

    /**
     * Interet de retard d'une journee, en precision interne.
     *
     * <p>Le montant n'est pas arrondi ici. L'arrondi quotidien d'un interet de retard derive comme
     * celui d'un interet courant, et pour la meme raison : c'est le <b>cumul</b> qui s'arrondit, et
     * l'ecart entre le cumul arrondi et ce qui a deja ete impute qui se comptabilise.
     */
    public static Money dailyInterest(Money basis, BigDecimal annualRatePercent,
                                      LocalDate day, LatePolicy policy) {
        if (!basis.isPositive() || annualRatePercent.signum() == 0) {
            return Money.zero(basis.currency());
        }
        BigDecimal fraction = policy.dayCount().dayFraction(day);
        return basis.times(annualRatePercent.movePointLeft(2)).times(fraction);
    }

    /**
     * Penalite due au titre d'une echeance impayee, percue une seule fois.
     *
     * @param overdue montant impaye de l'echeance au moment ou la franchise est franchie
     */
    public static Money penalty(Money overdue, LatePolicy policy) {
        Money zero = Money.zero(overdue.currency());
        if (!overdue.isPositive()) {
            return zero;
        }
        Money raw = switch (policy.penaltyMode()) {
            case NONE -> zero;
            case FLAT_PER_INSTALMENT -> policy.penaltyAmount();
            case PERCENT_OF_OVERDUE ->
                overdue.times(policy.penaltyRatePercent().movePointLeft(2));
        };
        if (raw.isZero()) {
            return zero;
        }
        Money bounded = raw;
        if (policy.penaltyFloor() != null && bounded.isLessThan(policy.penaltyFloor())) {
            bounded = policy.penaltyFloor();
        }
        if (policy.penaltyCap() != null && bounded.isGreaterThan(policy.penaltyCap())) {
            bounded = policy.penaltyCap();
        }
        return bounded.roundToCurrency();
    }

    /**
     * Part du cumul exact restant a imputer.
     *
     * <p>Meme procede que pour les interets courus : le cumul est tenu en precision interne, et
     * seule la difference entre son arrondi et ce qui a deja ete impute devient une ecriture. Un
     * arrondi quotidien perdrait quelques francs par jour et par contrat, de facon systematique.
     */
    public static Money postableDelta(Money cumulativePrecise, Money alreadyPosted) {
        return cumulativePrecise.roundToCurrency().minus(alreadyPosted);
    }
}
