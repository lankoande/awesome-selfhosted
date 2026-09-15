package io.corebanking.tfj;

/**
 * Nature d'un traitement d'arrete.
 *
 * <p>Le moteur est le meme — etapes ordonnees, idempotentes, tracees, annulables — et c'est la
 * nature du traitement qui fixe ce qu'il a le droit de faire de la date comptable : le traitement
 * de fin de journee ne traite que la journee courante et la fait basculer ; le traitement de fin
 * de mois porte sur un mois deja arrete jour par jour, et clot sa periode.
 */
public enum RunType {

    /** Traitement de fin de journee. */
    TFJ,

    /** Traitement de fin de mois : cloture d'une periode comptable. */
    TFM,

    /** Cloture annuelle : determination du resultat, dernier mois et exercice clos. */
    TFA
}
