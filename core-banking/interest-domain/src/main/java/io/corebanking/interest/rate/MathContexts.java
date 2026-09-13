package io.corebanking.interest.rate;

import java.math.MathContext;
import java.math.RoundingMode;

/** Precisions de calcul du moteur d'interets. */
public final class MathContexts {

    /** Divisions de taux : tres au-dela de l'echelle de comptabilisation. */
    public static final MathContext RATE = new MathContext(34, RoundingMode.HALF_EVEN);

    private MathContexts() {}
}
