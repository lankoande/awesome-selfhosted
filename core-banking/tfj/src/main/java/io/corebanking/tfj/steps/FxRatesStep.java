package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FxRevaluation;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;

/**
 * Cours de cloture.
 *
 * <p>L'etape verifie que chaque position de change a son cours du jour, et qu'aucune devise
 * detenue n'est sans position declaree. Elle vient avant tout calcul : une journee qui
 * comptabiliserait des interets en devise, puis decouvrirait a la revalorisation qu'il lui
 * manque un cours, aurait a etre annulee en entier. Le cours manquant, lui, se cote en une
 * minute.
 *
 * <p>Elle est bloquante parce qu'un cours absent ne se remplace par rien : ni par celui de la
 * veille — la revalorisation daterait alors d'hier —, ni par celui de la ligne, qui est
 * precisement ce que le referentiel controle.
 */
public final class FxRatesStep implements TfjStep {

    private final Database database;

    public FxRatesStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "FX_RATES";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<FxRevaluation.Gap> gaps = database.inTransaction(
            c -> FxRevaluation.gaps(c, context.legalEntityId(), context.businessDate()));
        List<String> anomalies = new ArrayList<>();
        for (FxRevaluation.Gap gap : gaps) {
            anomalies.add(gap.currency() + " : " + gap.reason());
        }
        return new StepResult(gaps.size(), 0, anomalies);
    }
}
