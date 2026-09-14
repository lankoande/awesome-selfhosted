package io.corebanking.kernel.time;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Periode de perception, bornes incluses.
 *
 * @param index rang depuis l'ancrage ; il identifie la periode de facon stable, contrairement a
 *              un numero d'ordre de traitement qui dependrait de l'historique des TFJ
 */
public record SchedulePeriod(int index, LocalDate start, LocalDate end) {

    public SchedulePeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (index < 0) {
            throw new IllegalArgumentException("Rang de periode negatif : " + index);
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Periode " + index + " fermee avant son ouverture : "
                                               + start + " au " + end);
        }
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * Nombre de jours de la periode couverts par la fenetre donnee, bornes incluses.
     *
     * <p>C'est le numerateur de la proratisation : un compte ouvert en cours de periode ne doit
     * supporter la commission que pour la fraction reellement servie.
     *
     * @param to borne de fin, nulle si la fenetre reste ouverte
     */
    public int daysWithin(LocalDate from, LocalDate to) {
        LocalDate lower = from == null || from.isBefore(start) ? start : from;
        LocalDate upper = to == null || to.isAfter(end) ? end : to;
        return upper.isBefore(lower) ? 0 : (int) ChronoUnit.DAYS.between(lower, upper) + 1;
    }

    @Override
    public String toString() {
        return "[" + start + " au " + end + "]";
    }
}
