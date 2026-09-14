package io.corebanking.fee;

import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.SchedulePeriod;
import java.util.Objects;

/**
 * Commission liquidee pour une periode.
 *
 * <p>Les trois montants sont conserves separement et non recalcules a l'affichage. Le net et la
 * taxe sont arrondis chacun pour son compte, et le total est leur somme : c'est ce qui garantit
 * que la taxe declaree a l'administration correspond exactement a celle qui a ete comptabilisee.
 *
 * @param gross       montant avant arrondi, apres assiette, prorata et bornes
 * @param chargedDays jours de la periode effectivement factures
 */
public record FeeAssessment(
    SchedulePeriod period,
    Money basisAmount,
    Money gross,
    Money net,
    Money tax,
    Money total,
    int chargedDays,
    int periodDays) {

    public FeeAssessment {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(total, "total");
        if (!total.equals(net.plus(tax))) {
            throw new IllegalArgumentException(
                "Total " + total + " different de la somme du net " + net + " et de la taxe " + tax
                + ". Le total n'est jamais arrondi pour lui-meme : il est la somme des composantes"
                + " imputees, sans quoi la taxe declaree ne serait pas celle qui a ete percue.");
        }
    }

    /** Vrai si la liquidation ne produit aucune ecriture : exoneration, prorata nul, montant nul. */
    public boolean isEmpty() {
        return total.isZero();
    }
}
