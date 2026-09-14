package io.corebanking.kernel.time;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Periodicite de perception d'une commission.
 *
 * <h2>Pourquoi les periodes sont calculees depuis l'ancrage, et jamais de proche en proche</h2>
 *
 * <p>La tentation naturelle est de deduire chaque echeance de la precedente : « la derniere
 * perception a eu lieu le 28 fevrier, donc la prochaine tombe le 28 mars ». C'est faux, et la
 * faute est silencieuse. Un compte ancre au 31 janvier voit sa deuxieme periode commencer le
 * 28 fevrier — le mois de fevrier n'a pas de 31. En repartant de ce 28, toutes les echeances
 * suivantes restent au 28 : le contrat a change de jour d'echeance a cause d'une annee bissextile,
 * et personne ne s'en apercoit avant la reclamation.
 *
 * <p>Ici chaque periode se calcule <b>directement depuis l'ancrage</b>, {@code anchor.plusMonths(n)}.
 * Le mois de fevrier ramene l'echeance au 28, et le mois de mars la rend au 31. Le jour d'echeance
 * du contrat est une propriete du contrat, pas un residu de l'historique des traitements.
 */
public enum Periodicity {

    DAILY(0),
    MONTHLY(1),
    QUARTERLY(3),
    SEMIANNUAL(6),
    ANNUAL(12);

    /** Nombre d'iterations de correction admis autour de l'estimation. */
    private static final int MAX_CORRECTION_STEPS = 4;

    private final int months;

    Periodicity(int months) {
        this.months = months;
    }

    /**
     * Nombre de periodes dans une annee.
     *
     * <p>Sert au <b>taux periodique proportionnel</b> : un taux annuel de 12 % donne 1 % par mois.
     * C'est une convention, pas une equivalence financiere — le taux actuariel equivalent serait
     * la racine douzieme de 1,12, soit 0,949 %. La convention proportionnelle est celle des
     * echeanciers a annuite constante ; l'ecart est assume et connu.
     */
    public int periodsPerYear() {
        return this == DAILY ? 365 : 12 / months;
    }

    /** Premier jour de la periode de rang donne. Le rang 0 est la periode ouverte par l'ancrage. */
    public LocalDate startOfPeriod(LocalDate anchor, int index) {
        Objects.requireNonNull(anchor, "anchor");
        if (index < 0) {
            throw new IllegalArgumentException("Rang de periode negatif : " + index);
        }
        return this == DAILY ? anchor.plusDays(index)
                             : anchor.plusMonths((long) months * index);
    }

    /** Periode de rang donne, bornes incluses. Les periodes successives sont jointives. */
    public SchedulePeriod period(LocalDate anchor, int index) {
        return new SchedulePeriod(index, startOfPeriod(anchor, index),
                             startOfPeriod(anchor, index + 1).minusDays(1));
    }

    /**
     * Rang de la periode contenant une date.
     *
     * <p>L'estimation par difference de mois est corrigee par encadrement : le rattrapage de fin
     * de mois decale l'estimation d'au plus une periode, et l'encadrement le rattrape sans
     * supposer de quel cote.
     */
    public int indexOfPeriodContaining(LocalDate anchor, LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(anchor)) {
            throw new IllegalArgumentException(
                "Le " + date + " precede l'ancrage " + anchor + " : aucune periode ne le contient.");
        }
        long estimate = this == DAILY
            ? ChronoUnit.DAYS.between(anchor, date)
            : ChronoUnit.MONTHS.between(anchor, date) / months;
        int index = (int) Math.max(0, estimate);

        for (int step = 0; step < MAX_CORRECTION_STEPS && startOfPeriod(anchor, index).isAfter(date);
             step++) {
            index--;
        }
        for (int step = 0; step < MAX_CORRECTION_STEPS
                           && !startOfPeriod(anchor, index + 1).isAfter(date); step++) {
            index++;
        }
        SchedulePeriod found = period(anchor, index);
        if (!found.contains(date)) {
            throw new IllegalStateException(
                "Encadrement de la periode du " + date + " depuis l'ancrage " + anchor
                + " non converge : " + found + ". Signaler ce cas, il revele un defaut de calendrier.");
        }
        return index;
    }
}
