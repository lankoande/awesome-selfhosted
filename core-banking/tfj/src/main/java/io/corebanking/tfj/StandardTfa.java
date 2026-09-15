package io.corebanking.tfj;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.steps.FiscalYearCloseStep;
import io.corebanking.tfj.steps.FullReconciliationStep;
import io.corebanking.tfj.steps.MonthCompleteStep;
import io.corebanking.tfj.steps.PeriodCloseStep;
import io.corebanking.tfj.steps.ResultDeterminationStep;
import io.corebanking.tfj.steps.YearCompleteStep;
import java.util.List;

/**
 * La cloture annuelle, sur le meme moteur que le TFJ et le TFM ({@link RunType#TFA}).
 *
 * <p>Elle porte la date de fin de l'exercice et clot son dernier mois elle-meme : les ecritures de
 * determination du resultat lui sont imputees avant que la periode ne se ferme. L'ordre est
 * celui de la preuve : le mois est complet, l'exercice est complet, le resultat est determine,
 * le journal est rejoue integralement — ecritures de resultat comprises —, puis le mois et
 * l'exercice sont clos. L'annulation contre-passe le resultat a la date de fin d'exercice, dans
 * la periode rouverte pour cela, et rouvre l'exercice en le disant.
 */
public final class StandardTfa {

    private StandardTfa() {}

    public static List<TfjStep> steps(Database database, PostingService postingService,
                                      BusinessCalendar calendar) {
        return List.of(
            new MonthCompleteStep(database, calendar),
            new YearCompleteStep(database),
            new ResultDeterminationStep(database, postingService),
            new FullReconciliationStep(database, StandardTfj.subLedgerChecks()),
            new PeriodCloseStep(database),
            new FiscalYearCloseStep(database));
    }

    public static TfjEngine engine(Database database, PostingService postingService,
                                   BusinessCalendar calendar) {
        return new TfjEngine(database, postingService, steps(database, postingService, calendar),
                             RunType.TFA);
    }
}
