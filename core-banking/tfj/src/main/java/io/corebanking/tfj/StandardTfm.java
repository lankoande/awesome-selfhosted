package io.corebanking.tfj;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.steps.FullReconciliationStep;
import io.corebanking.tfj.steps.MonthCompleteStep;
import io.corebanking.tfj.steps.PeriodCloseStep;
import java.util.List;

/**
 * Sequence standard du traitement de fin de mois.
 *
 * <p>Le mois a ete arrete jour par jour : interets courus, echeances, commissions, provisions
 * sont deja dans les comptes. Il reste a s'assurer qu'aucune journee ne manque, a rejouer le
 * journal depuis l'origine — le controle souverain, trop couteux pour chaque nuit — et a clore
 * la periode, apres quoi plus aucune ecriture ne peut y etre imputee.
 *
 * <p>Le traitement porte la date du dernier jour de la periode, ne touche pas a la date comptable
 * de l'entite, et son annulation rouvre la periode en le disant.
 */
public final class StandardTfm {

    private StandardTfm() {}

    public static List<TfjStep> steps(Database database, BusinessCalendar calendar) {
        return List.of(
            new MonthCompleteStep(database, calendar),
            new FullReconciliationStep(database, StandardTfj.subLedgerChecks()),
            new PeriodCloseStep(database));
    }

    public static TfjEngine engine(Database database, PostingService postingService,
                                   BusinessCalendar calendar) {
        return new TfjEngine(database, postingService, steps(database, calendar), RunType.TFM);
    }
}
