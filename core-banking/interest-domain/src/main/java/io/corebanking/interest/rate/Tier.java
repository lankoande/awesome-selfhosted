package io.corebanking.interest.rate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Tranche d'un bareme.
 *
 * @param from             borne inferieure incluse, exprimee dans la devise du solde
 * @param to               borne superieure exclue, {@code null} pour la derniere tranche
 * @param annualRatePercent taux annuel de la tranche, en pourcentage
 */
public record Tier(BigDecimal from, BigDecimal to, BigDecimal annualRatePercent) {

    public Tier {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(annualRatePercent, "annualRatePercent");
        if (from.signum() < 0) {
            throw new IllegalArgumentException("Borne inferieure negative : " + from);
        }
        if (to != null && to.compareTo(from) <= 0) {
            throw new IllegalArgumentException(
                "Tranche vide ou inversee : [" + from + ", " + to + "[");
        }
    }

    public static Tier of(String from, String to, String rate) {
        return new Tier(new BigDecimal(from), to == null ? null : new BigDecimal(to),
                        new BigDecimal(rate));
    }

    public boolean contains(BigDecimal amount) {
        return amount.compareTo(from) >= 0 && (to == null || amount.compareTo(to) < 0);
    }
}
