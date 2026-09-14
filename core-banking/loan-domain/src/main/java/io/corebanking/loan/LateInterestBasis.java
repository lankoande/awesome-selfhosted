package io.corebanking.loan;

/**
 * Assiette des interets de retard.
 *
 * <p>Aucune des deux ne contient les interets de retard deja courus. Les faire porter interet
 * serait de l'anatocisme : la capitalisation des interets echus est encadree, voire prohibee, dans
 * la plupart des droits de la zone, et un moteur qui la pratique par construction met la banque en
 * infraction sans que personne ne l'ait decide. L'exclusion est structurelle, pas parametree.
 */
public enum LateInterestBasis {

    /** Capital echu et non regle. C'est l'assiette la plus repandue. */
    OVERDUE_PRINCIPAL,

    /**
     * Capital, interets contractuels, commissions et assurances echus et non regles. Plus large,
     * donc plus chere pour l'emprunteur, et davantage exposee a la critique du juge.
     */
    TOTAL_OVERDUE
}
