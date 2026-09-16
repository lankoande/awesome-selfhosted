package io.corebanking.tfj.steps;

import io.corebanking.deposits.Tills;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controles prealables.
 *
 * <p>Ils s'executent avant tout calcul, et c'est leur raison d'etre : echouer apres avoir
 * comptabilise des millions de lignes coute une annulation complete, alors qu'echouer avant ne
 * coute rien. Les conditions verifiees ici sont toutes de nature a invalider l'arrete entier.
 */
public final class PreChecksStep implements TfjStep {

    private final Database database;
    private final io.corebanking.calendar.BusinessCalendar calendar;

    public PreChecksStep(Database database, io.corebanking.calendar.BusinessCalendar calendar) {
        this.database = database;
        this.calendar = calendar;
    }

    @Override
    public String name() {
        return "PRE_CHECKS";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> anomalies = new ArrayList<>();

        // La partition du mois traite est garantie, pas seulement controlee : sa creation est
        // idempotente et ne prejuge de rien. Le premier arrete d'une base neuve, ou d'une entite
        // dont la date a ete reprise, ne doit pas echouer sur un objet que le systeme sait creer.
        database.inTransaction(c -> {
            SchemaMigrator.ensurePartitions(c, context.businessDate(),
                                            context.businessDate().plusMonths(1));
            return null;
        });

        Optional<String> period = database.inTransaction(c ->
            Entities.periodStatus(c, context.legalEntityId(), context.businessDate()));
        if (period.isEmpty()) {
            anomalies.add("Aucune periode comptable ne couvre le " + context.businessDate()
                          + " : aucune ecriture d'arrete ne pourrait etre imputee. La bascule de "
                          + "journee l'ouvre normalement ; une date comptable reprise a la main "
                          + "l'exige aussi.");
        } else if (!"OPEN".equals(period.get()) && !"REOPENED".equals(period.get())) {
            anomalies.add("La periode comptable couvrant le " + context.businessDate()
                          + " est " + period.get() + " : la rouvrir est une decision comptable, "
                          + "pas un effet de bord de l'arrete.");
        }

        // Un compte d'attente non solde bloque la journee des qu'il depasse l'anciennete que la
        // politique tolere — ou des qu'il n'est pas solde, sans politique : sa justification
        // conditionne la sincerite de l'arrete.
        List<io.corebanking.deposits.Suspense.Item> suspens = database.inTransaction(
            c -> io.corebanking.deposits.Suspense.blockingSuspenseAccounts(
                c, context.legalEntityId(), context.businessDate(), calendar));
        if (!suspens.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (io.corebanking.deposits.Suspense.Item item : suspens) {
                detail.append(detail.isEmpty() ? "" : ", ").append(item.accountCode())
                    .append(" (").append(item.amount().roundToCurrency()).append(", ")
                    .append(item.ageBusinessDays()).append(" jour(s) ouvre(s)")
                    .append(item.owner() == null ? "" : ", responsable " + item.owner())
                    .append(')');
            }
            anomalies.add(suspens.size() + " compte(s) d'attente non solde(s) au-dela de "
                          + "l'anciennete toleree : " + detail + ". Leur justification "
                          + "conditionne la sincerite de l'arrete.");
        }

        // L'arrete de caisse precede l'arrete de la banque : une caisse qui a servi dans la
        // journee et n'est pas arretee laisse des especes non confrontees a leur solde.
        List<String> caisses = database.inTransaction(
            c -> Tills.movedAndUnclosed(c, context.legalEntityId(), context.businessDate()));
        if (!caisses.isEmpty()) {
            anomalies.add(caisses.size() + " caisse(s) mouvementee(s) le " + context.businessDate()
                          + " et non arretee(s) : " + caisses + ". L'arrete de caisse precede "
                          + "l'arrete de la banque.");
        }

        return new StepResult(3, 0, anomalies);
    }

}
