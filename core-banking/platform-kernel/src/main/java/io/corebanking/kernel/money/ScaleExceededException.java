package io.corebanking.kernel.money;

import java.math.BigDecimal;

/** Montant dont la precision depasse l'echelle interne autorisee. */
public class ScaleExceededException extends RuntimeException {
    public ScaleExceededException(BigDecimal amount, int maxScale) {
        super("Precision de " + amount.scale() + " decimales pour le montant " + amount.toPlainString()
              + " : maximum interne " + maxScale);
    }
}
