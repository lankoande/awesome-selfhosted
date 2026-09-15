package io.corebanking.interest.service;

import io.corebanking.kernel.time.Periodicity;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Fins de periode de reglement, calees sur le calendrier civil.
 *
 * <p>Les interets se reglent au dernier jour du mois, du trimestre, du semestre ou de l'annee —
 * jamais au dernier jour ouvre. Le traitement de fin de journee, lui, ne tourne que les jours
 * ouvres : un trimestre qui se termine un samedi est regle par le premier arrete qui le suit, et
 * le montant regle est celui couru <b>jusqu'au samedi</b>, pas jusqu'au jour du traitement.
 */
public final class SettlementCalendar {

    private SettlementCalendar() {}

    /** Derniere fin de periode a la date donnee ou avant elle. */
    public static LocalDate lastPeriodEndOnOrBefore(Periodicity periodicity, LocalDate date) {
        int months = monthsOf(periodicity);
        int endMonth = ((date.getMonthValue() - 1) / months + 1) * months;
        YearMonth current = YearMonth.of(date.getYear(), endMonth);
        LocalDate end = current.atEndOfMonth();
        if (!end.isAfter(date)) {
            return end;
        }
        return current.minusMonths(months).atEndOfMonth();
    }

    public static boolean isPeriodEnd(Periodicity periodicity, LocalDate date) {
        return lastPeriodEndOnOrBefore(periodicity, date).equals(date);
    }

    private static int monthsOf(Periodicity periodicity) {
        if (periodicity == Periodicity.DAILY) {
            throw new IllegalArgumentException("Pas de periode civile quotidienne");
        }
        return 12 / periodicity.periodsPerYear();
    }
}
