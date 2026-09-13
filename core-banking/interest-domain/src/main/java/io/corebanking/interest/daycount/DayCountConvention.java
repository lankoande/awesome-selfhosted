package io.corebanking.interest.daycount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Conventions de decompte usuelles.
 *
 * <p>Elles ne sont pas interchangeables : sur un capital de 10 000 000 XOF a 8 %, une annee en
 * ACT/360 rapporte 811 111 XOF la ou ACT/365 en rapporte 800 000. L'ecart de 11 111 XOF est
 * entierement conventionnel — d'ou la necessite de le contractualiser, et non de le choisir a
 * l'implementation.
 */
public enum DayCountConvention implements DayCount {

    /** Jours reels sur 360. Usage courant sur les credits et les agios. */
    ACT_360 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate endExclusive) {
            return actualDays(start, endExclusive).divide(new BigDecimal("360"), CONTEXT);
        }
    },

    /** Jours reels sur 365, y compris les annees bissextiles. Usage courant sur l'epargne. */
    ACT_365 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate endExclusive) {
            return actualDays(start, endExclusive).divide(new BigDecimal("365"), CONTEXT);
        }
    },

    /**
     * Jours reels sur la duree reelle de l'annee. Chaque journee est rapportee a l'annee a
     * laquelle elle appartient : 1/366 en annee bissextile, 1/365 sinon. Une periode a cheval sur
     * deux annees est donc decoupee.
     */
    ACT_ACT_ISDA {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate endExclusive) {
            BigDecimal total = BigDecimal.ZERO;
            LocalDate cursor = start;
            while (cursor.isBefore(endExclusive)) {
                LocalDate yearEnd = LocalDate.of(cursor.getYear(), 12, 31).plusDays(1);
                LocalDate segmentEnd = yearEnd.isBefore(endExclusive) ? yearEnd : endExclusive;
                BigDecimal daysInYear = new BigDecimal(cursor.isLeapYear() ? 366 : 365);
                total = total.add(actualDays(cursor, segmentEnd).divide(daysInYear, CONTEXT));
                cursor = segmentEnd;
            }
            return total;
        }
    },

    /**
     * Mois de 30 jours, annee de 360 (convention americaine).
     *
     * <p>Consequence assumee et souvent mal implementee : <b>tout mois vaut 30/360</b>, quelle que
     * soit sa duree reelle. Un mois de 31 jours ne rapporte que 30 journees ; fevrier en rapporte
     * 30 alors qu'il n'en compte que 28. Une implementation qui accorde 1/360 par jour calendaire
     * surfacture sept journees par an et sous-remunere fevrier.
     */
    THIRTY_360_US {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate endExclusive) {
            int d1 = Math.min(start.getDayOfMonth(), 30);
            int d2 = endExclusive.getDayOfMonth();
            if (d2 == 31 && d1 == 30) {
                d2 = 30;
            }
            long days = 360L * (endExclusive.getYear() - start.getYear())
                      + 30L * (endExclusive.getMonthValue() - start.getMonthValue())
                      + (d2 - d1);
            return new BigDecimal(days).divide(new BigDecimal("360"), CONTEXT);
        }
    },

    /** Variante europeenne : les jours 31 sont systematiquement ramenes a 30, aux deux bornes. */
    THIRTY_E_360 {
        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate endExclusive) {
            int d1 = Math.min(start.getDayOfMonth(), 30);
            int d2 = Math.min(endExclusive.getDayOfMonth(), 30);
            long days = 360L * (endExclusive.getYear() - start.getYear())
                      + 30L * (endExclusive.getMonthValue() - start.getMonthValue())
                      + (d2 - d1);
            return new BigDecimal(days).divide(new BigDecimal("360"), CONTEXT);
        }
    };

    static BigDecimal actualDays(LocalDate start, LocalDate endExclusive) {
        return new BigDecimal(ChronoUnit.DAYS.between(start, endExclusive));
    }
}
