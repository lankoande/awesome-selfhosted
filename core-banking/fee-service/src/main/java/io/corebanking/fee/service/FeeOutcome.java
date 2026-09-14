package io.corebanking.fee.service;

/** Denouement d'une liquidation de commission. */
public enum FeeOutcome {

    /** Percue, une ecriture existe. */
    COLLECTED,

    /** Percue en laissant le compte passer debiteur, sur decision de parametrage. */
    FORCED,

    /** Due, exoneree : le montant est conserve, il mesure le cout du geste commercial. */
    WAIVED,

    /** Reportee faute de provision : representee a chaque traitement suivant. */
    DEFERRED,

    /** Abandonnee faute de provision, sans report : perte definitive, consignee. */
    REJECTED,

    /** Reportee trop longtemps : creance abandonnee au terme parametre. */
    WRITTEN_OFF,

    /** Periode examinee, rien n'etait du : prorata nul, assiette nulle, bareme a zero. */
    NOT_DUE,

    /** Neutralisee par l'annulation du traitement qui l'a produite. La periode redevient due. */
    CANCELLED;

    public boolean isSettled() {
        return this == COLLECTED || this == FORCED;
    }
}
