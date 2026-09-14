package io.corebanking.fee;

/** Traitement d'une periode incompletement servie. */
public enum Proration {

    /** La commission est due en entier des lors que la periode s'acheve. */
    NONE,

    /**
     * La commission est reduite au prorata des jours reellement servis : ouverture ou cloture du
     * compte en cours de periode, entree ou sortie d'exoneration.
     */
    ACTUAL_DAYS
}
