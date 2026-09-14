package io.corebanking.loan;

/**
 * Mode de calcul de la penalite de retard.
 *
 * <p>La penalite se distingue de l'interet de retard : elle se percoit <b>une fois par echeance
 * impayee</b>, pas chaque jour. Confondre les deux revient soit a facturer une penalite
 * quotidienne, soit a ne jamais facturer l'interet de retard.
 */
public enum PenaltyMode {

    NONE,

    /** Forfait par echeance impayee. */
    FLAT_PER_INSTALMENT,

    /** Pourcentage du montant impaye de l'echeance, encadre par un plancher et un plafond. */
    PERCENT_OF_OVERDUE
}
