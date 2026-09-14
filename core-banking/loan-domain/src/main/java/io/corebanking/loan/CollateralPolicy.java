package io.corebanking.loan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Regime d'eligibilite d'un type de surete.
 *
 * <h2>Pourquoi la quotite n'est pas saisie sur la garantie</h2>
 *
 * <p>La decote appliquee a une surete est une regle du referentiel, pas une donnee du dossier.
 * Laisser un agent la saisir revient a lui laisser decider du niveau de provision de son propre
 * portefeuille : une hypotheque retenue a 100 % au lieu de 50 % divise la provision par deux, et
 * rien dans l'ecriture ne le signale. La quotite vient donc du type de surete, et le type vient
 * d'une liste paramétree.
 *
 * @param maxValuationAgeMonths anciennete maximale de l'expertise. Au-dela, la surete cesse d'etre
 *                              eligible : une valeur d'il y a dix ans n'est pas une valeur. Zero
 *                              signifie que ce type de surete ne se revalorise pas — une caution
 *                              bancaire, un nantissement d'especes.
 */
public record CollateralPolicy(
    String kind,
    String label,
    BigDecimal eligibleRatePercent,
    int maxValuationAgeMonths) {

    public CollateralPolicy {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(eligibleRatePercent, "eligibleRatePercent");
        if (eligibleRatePercent.signum() < 0
            || eligibleRatePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(
                "Surete " + kind + " : quotite de " + eligibleRatePercent + " % hors de [0..100]. "
                + "Au-dela de cent, la garantie couvrirait plus que sa propre valeur.");
        }
        if (maxValuationAgeMonths < 0) {
            throw new IllegalArgumentException(
                "Surete " + kind + " : anciennete maximale negative.");
        }
    }

    /** Vrai si l'expertise est trop ancienne pour que la surete reste eligible. */
    public boolean valuationStale(LocalDate valuedOn, LocalDate at) {
        if (maxValuationAgeMonths == 0) {
            return false;
        }
        return valuedOn == null || valuedOn.plusMonths(maxValuationAgeMonths).isBefore(at);
    }
}
