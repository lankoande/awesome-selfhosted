package io.corebanking.calendar;

import java.time.LocalDate;

/**
 * Traitement d'une date qui tombe un jour non ouvre.
 *
 * <p>Le choix se lit dans le contrat, jamais dans le code. Sur une echeance de fin de mois qui
 * tombe un dimanche, {@code FOLLOWING} la reporte au premier du mois suivant et fait basculer
 * l'echeance d'un exercice a l'autre ; {@code MODIFIED_FOLLOWING} la ramene au vendredi et reste
 * dans le mois. Les deux sont licites, aucune n'est un defaut raisonnable.
 */
public enum BusinessDayConvention {

    /** Aucun ajustement. Les interets courent aussi les jours non ouvres. */
    UNADJUSTED {
        @Override
        public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
            return date;
        }
    },

    /** Report au premier jour ouvre suivant. */
    FOLLOWING {
        @Override
        public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
            return calendar.nextBusinessDayOrSame(date);
        }
    },

    /**
     * Report au jour ouvre suivant, sauf si l'on change de mois : dans ce cas, recul au jour ouvre
     * precedent. Convention dominante sur les echeanciers, parce qu'elle preserve le rattachement
     * de l'echeance a son mois.
     */
    MODIFIED_FOLLOWING {
        @Override
        public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
            LocalDate next = calendar.nextBusinessDayOrSame(date);
            return next.getMonthValue() == date.getMonthValue() && next.getYear() == date.getYear()
                ? next
                : calendar.previousBusinessDayOrSame(date);
        }
    },

    /** Recul au premier jour ouvre precedent. */
    PRECEDING {
        @Override
        public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
            return calendar.previousBusinessDayOrSame(date);
        }
    },

    /** Recul au jour ouvre precedent, sauf changement de mois : report au suivant. */
    MODIFIED_PRECEDING {
        @Override
        public LocalDate adjust(LocalDate date, BusinessCalendar calendar) {
            LocalDate previous = calendar.previousBusinessDayOrSame(date);
            return previous.getMonthValue() == date.getMonthValue()
                   && previous.getYear() == date.getYear()
                ? previous
                : calendar.nextBusinessDayOrSame(date);
        }
    };

    public abstract LocalDate adjust(LocalDate date, BusinessCalendar calendar);
}
