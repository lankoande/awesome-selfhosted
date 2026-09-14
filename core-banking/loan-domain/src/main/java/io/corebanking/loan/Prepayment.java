package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Remboursement anticipe de capital.
 *
 * <h2>Ce que le calcul refuse de deviner</h2>
 *
 * <p>Un remboursement anticipe n'est pas un reglement d'echeance. Il vient <b>en diminution du
 * capital restant du</b>, et oblige a reconstruire l'echeancier des echeances a venir. Le traiter
 * comme un versement ordinaire l'imputerait sur les echeances echues et ne changerait rien au
 * plan : le client paierait d'avance sans rien economiser.
 *
 * <p>L'indemnite est <b>plafonnee par la loi</b> dans la plupart des juridictions de la zone, et le
 * plafond porte a la fois sur un pourcentage du capital rembourse et sur un nombre de mois
 * d'interets. Les deux plafonds s'appliquent, et c'est le plus favorable a l'emprunteur qui
 * l'emporte — un seul des deux laisserait passer la moitie des abus.
 */
public record Prepayment(
    LocalDate on,
    Money principalRepaid,
    Money indemnity,
    PrepaymentMode mode,
    AmortisationSchedule newSchedule) {

    public Prepayment {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(mode, "mode");
        if (!principalRepaid.isPositive()) {
            throw new IllegalArgumentException(
                "Remboursement anticipe de " + principalRepaid + " : un versement nul ou negatif "
                + "n'est pas un remboursement.");
        }
        if (indemnity.isNegative()) {
            throw new IllegalArgumentException("Indemnite negative : " + indemnity);
        }
    }

    /** Somme reellement demandee a l'emprunteur : le capital rembourse et son indemnite. */
    public Money totalDue() {
        return principalRepaid.plus(indemnity);
    }

    /** Vrai si le remboursement solde le credit : il ne reste aucune echeance. */
    public boolean settlesLoan() {
        return newSchedule == null;
    }

    /**
     * Indemnite de remboursement anticipe, sous double plafond.
     *
     * @param ratePercent      pourcentage du capital rembourse
     * @param capPercent       plafond exprime en pourcentage du capital rembourse, nul si absent
     * @param capMonths        plafond exprime en mois d'interets au taux du contrat, nul si absent
     * @param annualRatePercent taux nominal du contrat, assiette du plafond en mois d'interets
     */
    public static Money indemnity(Money principalRepaid, BigDecimal ratePercent,
                                  BigDecimal capPercent, BigDecimal capMonths,
                                  BigDecimal annualRatePercent) {
        Money zero = Money.zero(principalRepaid.currency());
        if (ratePercent == null || ratePercent.signum() <= 0) {
            return zero;
        }
        Money raw = principalRepaid.times(ratePercent.movePointLeft(2)).roundToCurrency();

        List<Money> ceilings = new ArrayList<>(2);
        if (capPercent != null && capPercent.signum() > 0) {
            ceilings.add(principalRepaid.times(capPercent.movePointLeft(2)).roundToCurrency());
        }
        if (capMonths != null && capMonths.signum() > 0 && annualRatePercent != null) {
            ceilings.add(principalRepaid.times(annualRatePercent.movePointLeft(2))
                             .times(capMonths).dividedBy(BigDecimal.valueOf(12))
                             .roundToCurrency());
        }
        // Les deux plafonds s'appliquent : c'est le plus bas qui l'emporte, donc le plus favorable
        // a l'emprunteur. N'en retenir qu'un laisserait passer la moitie des depassements.
        Money bounded = raw;
        for (Money ceiling : ceilings) {
            if (bounded.isGreaterThan(ceiling)) {
                bounded = ceiling;
            }
        }
        return bounded;
    }
}
