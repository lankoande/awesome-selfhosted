package io.corebanking.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Calendrier des jours ouvres d'une entite.
 *
 * <p>Le week-end est parametre et non deduit : il ne tombe pas partout le samedi et le dimanche, et
 * un socle destine a plusieurs pays ne peut pas le presumer.
 *
 * <p>Les jours feries sont des dates explicites, saisies annee par annee. Les calculer — Paques et
 * ses derives, les fetes mobiles du calendrier lunaire — supposerait d'embarquer des regles
 * astronomiques et religieuses dans un core banking, et de les maintenir. Les feries sont declares
 * par le referentiel, comme le reste du parametrage ; leur absence au-dela de l'horizon saisi est
 * signalee plutot que devinee.
 */
public final class BusinessCalendar {

    private final String code;
    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;
    private final LocalDate coverageFrom;
    private final LocalDate coverageTo;

    public BusinessCalendar(String code, Set<DayOfWeek> weekend, Set<LocalDate> holidays,
                            LocalDate coverageFrom, LocalDate coverageTo) {
        this.code = Objects.requireNonNull(code, "code");
        this.weekend = Set.copyOf(Objects.requireNonNull(weekend, "weekend"));
        this.holidays = Set.copyOf(Objects.requireNonNull(holidays, "holidays"));
        this.coverageFrom = coverageFrom;
        this.coverageTo = coverageTo;
    }

    /** Calendrier sans ferie, week-end au samedi et dimanche. Utile aux tests et a l'amorcage. */
    public static BusinessCalendar standardWeekendOnly(String code) {
        return new BusinessCalendar(code, Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(),
                                    null, null);
    }

    public String code() {
        return code;
    }

    public boolean isBusinessDay(LocalDate date) {
        requireCovered(date);
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    public LocalDate nextBusinessDayOrSame(LocalDate date) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public LocalDate previousBusinessDayOrSame(LocalDate date) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    public LocalDate nextBusinessDay(LocalDate date) {
        return nextBusinessDayOrSame(date.plusDays(1));
    }

    /**
     * Ajoute un nombre de <b>jours ouvres</b>, les jours non ouvres n'etant pas comptes.
     *
     * <p>A distinguer du decalage en jours calendaires suivi d'un ajustement : « deux jours
     * ouvres » a partir d'un vendredi donne le mardi, tandis que « deux jours calendaires ajustes
     * au suivant » donne le lundi. L'ecart d'une journee se traduit en agios.
     */
    public LocalDate addBusinessDays(LocalDate date, int days) {
        if (days == 0) {
            return date;
        }
        LocalDate candidate = date;
        int step = days > 0 ? 1 : -1;
        for (int i = 0; i < Math.abs(days); i++) {
            do {
                candidate = candidate.plusDays(step);
            } while (!isBusinessDay(candidate));
        }
        return candidate;
    }

    /**
     * Le nombre de jours ouvres ecoules de {@code from} (exclu) a {@code to} (inclus) : l'age
     * d'un suspens ne depuis {@code from}, en jours ouvres. Zero si {@code to} ne depasse pas
     * {@code from}.
     */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        int days = 0;
        // Un depart anterieur a la periode couverte compte depuis son debut : l'age rendu est
        // alors un minimum, ce qui suffit a un suspens plus vieux que toute tolerance — et vaut
        // mieux qu'un refus de repondre au milieu d'un arrete.
        LocalDate candidate = coverageFrom != null && from.isBefore(coverageFrom)
            ? coverageFrom.minusDays(1) : from;
        while (candidate.isBefore(to)) {
            candidate = candidate.plusDays(1);
            if (isBusinessDay(candidate)) {
                days++;
            }
        }
        return days;
    }

    /**
     * Un calendrier ne repond pas au-dela de sa periode de saisie.
     *
     * <p>Presumer qu'un jour non saisi est ouvre reviendrait a traiter le 1er janvier comme un
     * jour ordinaire des que la saisie des feries prend du retard — et l'erreur ne se verrait
     * qu'a la reclamation.
     */
    private void requireCovered(LocalDate date) {
        if (coverageFrom != null && date.isBefore(coverageFrom)) {
            throw new CoverageException(code, date, coverageFrom, coverageTo);
        }
        if (coverageTo != null && date.isAfter(coverageTo)) {
            throw new CoverageException(code, date, coverageFrom, coverageTo);
        }
    }

    /** Date hors de la periode couverte par le calendrier. */
    public static class CoverageException extends RuntimeException {
        public CoverageException(String code, LocalDate date, LocalDate from, LocalDate to) {
            super("Le calendrier " + code + " ne couvre pas le " + date + " (saisi du " + from
                  + " au " + to + "). Les jours feries doivent etre saisis avant d'etre traverses : "
                  + "les presumer ouvres produirait des dates de valeur fausses.");
        }
    }
}
