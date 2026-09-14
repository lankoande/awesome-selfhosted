package io.corebanking.loan;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Classe de risque d'une grille reglementaire.
 *
 * @param ordinal rang de degradation : zero pour la classe saine, croissant vers le pire. C'est
 *                lui qui ordonne la contagion, et non le code, qui varie d'un pays a l'autre.
 * @param toDays  borne haute incluse du retard, nulle pour la derniere classe
 */
public record RiskBucket(
    int ordinal,
    String code,
    String label,
    int fromDays,
    Integer toDays,
    BigDecimal provisionRatePercent,
    boolean performing) {

    public RiskBucket {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(provisionRatePercent, "provisionRatePercent");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Rang de classe negatif : " + ordinal);
        }
        if (fromDays < 0) {
            throw new IllegalArgumentException("Borne basse negative pour la classe " + code);
        }
        if (toDays != null && toDays < fromDays) {
            throw new IllegalArgumentException(
                "Classe " + code + " : borne haute " + toDays + " sous la borne basse " + fromDays);
        }
        if (provisionRatePercent.signum() < 0
            || provisionRatePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException(
                "Classe " + code + " : taux de provision " + provisionRatePercent
                + " % hors de [0..100]");
        }
    }

    public boolean covers(long daysPastDue) {
        return daysPastDue >= fromDays && (toDays == null || daysPastDue <= toDays);
    }

    @Override
    public String toString() {
        return code + " [" + fromDays + ".." + (toDays == null ? "" : toDays) + " j, "
               + provisionRatePercent + " %]";
    }
}
