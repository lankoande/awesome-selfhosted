package io.corebanking.interest.daycount;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Convention de decompte des jours : la fraction d'annee separant deux dates.
 *
 * <p>Le choix de la convention change le montant facture au client. Il est porte par le contrat,
 * jamais par le code appelant, et ne peut pas changer retroactivement.
 *
 * <p>La borne de fin est <b>exclue</b>. Un intert court du jour de valeur inclus au jour de valeur
 * suivant exclu : c'est ce qui garantit qu'aucune journee n'est ni comptee deux fois, ni oubliee,
 * lorsqu'on enchaine les periodes.
 */
public interface DayCount {

    /** Precision des divisions : largement superieure a l'echelle de comptabilisation. */
    MathContext CONTEXT = new MathContext(34, RoundingMode.HALF_EVEN);

    BigDecimal yearFraction(LocalDate start, LocalDate endExclusive);

    /** Fraction d'annee d'une seule journee de valeur. */
    default BigDecimal dayFraction(LocalDate day) {
        return yearFraction(day, day.plusDays(1));
    }
}
