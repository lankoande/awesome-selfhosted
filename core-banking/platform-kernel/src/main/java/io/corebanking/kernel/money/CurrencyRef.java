package io.corebanking.kernel.money;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * Devise et ses regles d'arrondi.
 *
 * <p>{@code scale} est le nombre de decimales de la subdivision officielle : 0 pour le XOF
 * et le XAF (aucune subdivision), 2 pour l'EUR et l'USD, 3 pour le TND. Il ne s'agit pas
 * d'une preference d'affichage : c'est la seule echelle a laquelle un montant peut etre
 * comptabilise.
 */
public record CurrencyRef(String code, int scale, RoundingMode roundingMode) {

    public CurrencyRef {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (code.length() != 3) {
            throw new IllegalArgumentException("Code devise ISO 4217 attendu sur 3 lettres : " + code);
        }
        if (scale < 0 || scale > Money.MAX_INTERNAL_SCALE) {
            throw new IllegalArgumentException(
                "Echelle de devise hors bornes [0.." + Money.MAX_INTERNAL_SCALE + "] : " + scale);
        }
    }

    public static CurrencyRef of(String code, int scale) {
        return new CurrencyRef(code, scale, RoundingMode.HALF_EVEN);
    }

    @Override
    public String toString() {
        return code;
    }
}
