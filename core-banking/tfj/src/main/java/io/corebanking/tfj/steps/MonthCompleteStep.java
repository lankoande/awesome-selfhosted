package io.corebanking.tfj.steps;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.tfj.Runs;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Le mois est complet : chaque jour ouvre de la periode a ete arrete par un traitement de fin de
 * journee termine.
 *
 * <p>Une journee manquante — un TFJ en echec jamais repris, une date comptable reprise a la main —
 * laisserait un mois clos sur des interets, des echeances et des commissions jamais constates.
 * L'etape nomme chaque journee absente. Les journees anterieures a la premiere jamais arretee par
 * l'entite ne sont pas exigees : une entite qui demarre en cours de mois ne doit rien aux jours
 * d'avant.
 */
public final class MonthCompleteStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;
    private final BusinessCalendar calendar;

    public MonthCompleteStep(Database database, BusinessCalendar calendar) {
        this.database = database;
        this.calendar = calendar;
    }

    @Override
    public String name() {
        return "MONTH_COMPLETE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> anomalies = new ArrayList<>();
        long examined = database.inTransaction(c -> {
            Optional<LocalDate[]> bounds = Entities.periodBounds(c, context.legalEntityId(),
                                                                 context.businessDate());
            if (bounds.isEmpty()) {
                anomalies.add("Aucune periode comptable ne couvre le " + context.businessDate());
                return 0L;
            }
            LocalDate start = bounds.get()[0];
            LocalDate end = bounds.get()[1];
            Optional<LocalDate> first = Runs.firstDay(c, context.legalEntityId());
            if (first.isEmpty()) {
                anomalies.add("Aucune journee n'a jamais ete arretee pour cette entite : rien a "
                              + "clore.");
                return 0L;
            }
            LocalDate from = first.get().isAfter(start) ? first.get() : start;
            Set<LocalDate> completed = Set.copyOf(Runs.completedDays(c, context.legalEntityId(),
                                                                     from, end));
            long required = 0;
            for (LocalDate day = from; !day.isAfter(end); day = day.plusDays(1)) {
                if (!calendar.isBusinessDay(day)) {
                    continue;
                }
                required++;
                if (!completed.contains(day) && anomalies.size() < MAX_REPORTED) {
                    anomalies.add("Le " + day + " n'a pas de traitement de fin de journee termine.");
                }
            }
            if (anomalies.size() >= MAX_REPORTED) {
                anomalies.add("... liste tronquee.");
            }
            return required;
        });
        return new StepResult(examined, 0, anomalies);
    }
}
