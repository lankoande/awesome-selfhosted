package io.corebanking.fee;

import java.time.LocalDate;

/** Moment de perception d'une commission dans sa periode. */
public enum FeeTiming {

    /**
     * A terme echu : la commission est prelevee le dernier jour de la periode qu'elle couvre.
     * C'est le regime des frais assis sur une assiette constatee — un solde moyen, un plus fort
     * decouvert — qui ne sont connus qu'a la cloture de la periode.
     */
    IN_ARREARS,

    /**
     * A terme a echoir : la commission est prelevee le premier jour de la periode qu'elle couvre.
     * C'est le regime des forfaits — une cotisation de carte, des frais de tenue de compte —
     * factures d'avance.
     *
     * <p>Consequence a assumer : si le compte est clos en cours de periode, la commission a deja
     * ete percue et la restitution du prorata est une operation distincte, pas un simple
     * non-prelevement.
     */
    IN_ADVANCE;

    public LocalDate chargeDate(FeePeriod period) {
        return this == IN_ARREARS ? period.end() : period.start();
    }
}
