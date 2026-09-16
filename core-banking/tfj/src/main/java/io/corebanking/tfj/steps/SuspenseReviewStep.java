package io.corebanking.tfj.steps;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.deposits.Suspense;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Revue des suspens.
 *
 * <p>Ce qui attend le correspondant — ordres non regles, remises non encaissees, prelevements
 * non regles, comptes d'attente non soldes — est passe en revue avec son anciennete en jours
 * ouvres et le responsable que la politique lui donne. Les retards sont rendus en anomalies,
 * par nature, avec le plus ancien : la liste de travail du lendemain. L'etape ne comptabilise
 * rien et ne bloque pas la journee ; ce qui la bloque — un compte d'attente en retard — est
 * dit par les controles prealables, avant tout calcul.
 */
public final class SuspenseReviewStep implements TfjStep {

    private final Database database;
    private final BusinessCalendar calendar;

    public SuspenseReviewStep(Database database, BusinessCalendar calendar) {
        this.database = database;
        this.calendar = calendar;
    }

    @Override
    public String name() {
        return "SUSPENSE_REVIEW";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<Suspense.Item> items = database.inTransaction(c -> Suspense.items(
            c, context.legalEntityId(), context.businessDate(), calendar));
        Map<Suspense.Kind, List<Suspense.Item>> overdue = new EnumMap<>(Suspense.Kind.class);
        for (Suspense.Item item : items) {
            if (item.overdue()) {
                overdue.computeIfAbsent(item.kind(), k -> new ArrayList<>()).add(item);
            }
        }
        List<String> anomalies = new ArrayList<>();
        long late = 0;
        for (Map.Entry<Suspense.Kind, List<Suspense.Item>> entry : overdue.entrySet()) {
            Suspense.Item oldest = entry.getValue().get(0);
            late += entry.getValue().size();
            anomalies.add(entry.getKey() + " : " + entry.getValue().size() + " suspens en retard,"
                          + " le plus ancien depuis le " + oldest.since() + " ("
                          + oldest.ageBusinessDays() + " jour(s) ouvre(s), tolere "
                          + oldest.maxBusinessDays() + ") — responsable : " + oldest.owner());
        }
        return new StepResult(items.size(), late, anomalies);
    }
}
