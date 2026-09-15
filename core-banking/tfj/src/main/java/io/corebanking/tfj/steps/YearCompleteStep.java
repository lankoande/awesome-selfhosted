package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * L'exercice est complet : chacun de ses mois, hors le dernier, est clos par un arrete mensuel ;
 * le dernier est arrete jour par jour (etape precedente) et encore ouvert, pour recevoir les
 * ecritures de resultat.
 *
 * <p>Un mois non clos au milieu d'un exercice est un mois dont le rejeu integral n'a jamais ete
 * confronte aux sous-livres : clore l'exercice par-dessus fixerait un resultat que rien n'a
 * prouve. L'etape nomme chaque mois manquant.
 */
public final class YearCompleteStep implements TfjStep {

    private final Database database;

    public YearCompleteStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "YEAR_COMPLETE";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> anomalies = new ArrayList<>();
        long examined = database.inTransaction(c -> {
            FiscalYears.FiscalYear year = FiscalYears.endingOn(c, context.legalEntityId(),
                                                               context.businessDate())
                .orElse(null);
            if (year == null) {
                anomalies.add("Aucun exercice ne se termine le " + context.businessDate());
                return 0L;
            }
            if ("CLOSED".equals(year.status())) {
                anomalies.add("L'exercice du " + year.start() + " au " + year.end()
                              + " est deja clos");
            }
            List<String> open = FiscalYears.periodsNotClosedBefore(c, context.legalEntityId(),
                                                                   year.start(), year.end());
            for (String period : open) {
                anomalies.add("Periode " + period + " non close : l'arrete mensuel precede la "
                              + "cloture annuelle");
            }
            String last = Entities.periodStatus(c, context.legalEntityId(), year.end())
                .orElse("ABSENTE");
            if ("CLOSED".equals(last)) {
                anomalies.add("Le dernier mois de l'exercice est deja clos : les ecritures de "
                              + "resultat ne peuvent plus lui etre imputees");
            }
            return 1L + open.size();
        });
        return new StepResult(examined, 0, anomalies);
    }
}
