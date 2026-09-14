package io.corebanking.tfj;

/** Mode d'execution d'un traitement. */
public enum RunMode {
    /** Traitement reel : les ecritures sont comptabilisees, la journee bascule. */
    REAL,
    /**
     * TFJ a blanc. Le traitement emprunte <b>exactement</b> le chemin du TFJ reel — memes
     * controles, memes calculs, memes ecritures, memes contraintes de base — et l'ensemble est
     * annule a la fin. Un mode simulation qui court-circuiterait la comptabilisation ne testerait
     * pas ce qui casse en production, et ne prouverait donc rien.
     */
    DRY_RUN
}
