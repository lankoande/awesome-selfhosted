package io.corebanking.interest.accrual;

import io.corebanking.kernel.money.Money;
import java.util.List;

/**
 * Resultat d'un calcul d'interets sur une periode.
 *
 * @param total montant en <b>precision interne</b>, non arrondi. L'arrondi n'intervient qu'a la
 *              capitalisation : arrondir ici, puis de nouveau plus tard, arrondirait deux fois.
 */
public record PeriodAccrual(InterestBasis basis, AccrualSide side, List<DailyAccrual> days,
                            Money total) {

    public PeriodAccrual {
        days = List.copyOf(days);
    }

    /** Montant effectivement comptabilisable, arrondi a l'echelle de la devise. */
    public Money bookableAmount() {
        return total.roundToCurrency();
    }

    /** Ecart d'arrondi, a imputer sur le compte de difference d'arrondi. */
    public Money roundingRemainder() {
        return total.roundingRemainder();
    }
}
