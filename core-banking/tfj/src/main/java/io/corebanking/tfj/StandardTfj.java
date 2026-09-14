package io.corebanking.tfj;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.fee.service.FeeChargingService;
import io.corebanking.loan.service.LoanService;
import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.steps.BalanceSnapshotStep;
import io.corebanking.tfj.steps.FeeChargingStep;
import io.corebanking.tfj.steps.InterestAccrualStep;
import io.corebanking.tfj.steps.LoanScheduleStep;
import io.corebanking.tfj.steps.OpenNextDayStep;
import io.corebanking.tfj.steps.PreChecksStep;
import io.corebanking.tfj.steps.ReconciliationStep;
import java.util.List;

/**
 * Sequence standard du TFJ.
 *
 * <p>L'ordre n'est pas arbitraire, et trois contraintes le fixent :
 *
 * <ul>
 *   <li><b>Les controles prealables d'abord.</b> Echouer avant tout calcul ne coute rien ; echouer
 *       apres avoir comptabilise coute une annulation complete.</li>
 *   <li><b>Les commissions avant les interets.</b> Une commission est imputee en date de valeur du
 *       jour : elle entre donc dans le solde sur lequel les interets de ce jour se calculent.
 *       L'ordre inverse remunererait un solde que le client n'a plus, et l'ecart se reporterait
 *       sur toute la serie des jours suivants.</li>
 *   <li><b>Les echeances de credit apres les commissions et avant les interets.</b> Le
 *       prelevement d'une echeance reduit le solde du compte de reglement, et donc les interets
 *       crediteurs de la journee. Le placer apres le calcul des interets remunererait un solde que
 *       le client n'a plus.</li>
 *   <li><b>Les interets avant le cliche des soldes.</b> Le cliche doit refleter la journee arretee,
 *       interets compris — sinon le solde fige et le solde rejoue divergeront des le lendemain.</li>
 *   <li><b>La reconciliation avant la bascule.</b> C'est tout le mecanisme : tant que les controles
 *       ne sont pas verts, la journee ne bascule pas, et le systeme refuse de travailler sur la
 *       suivante.</li>
 * </ul>
 *
 * <p>La sequence reste courte. Les etapes que le dossier prevoit et qui manquent encore —
 * penalites de retard, classification, provisionnement, revalorisation de change, dormance,
 * expiration des blocages, revue KYC — s'inserent ici sans toucher au moteur.
 */
public final class StandardTfj {

    private StandardTfj() {}

    public static List<TfjStep> steps(Database database,
                                      BatchInterestAccrualService interestService,
                                      FeeChargingService feeService, LoanService loanService,
                                      BusinessCalendar calendar) {
        return List.of(
            new PreChecksStep(database),
            new FeeChargingStep(database, feeService),
            new LoanScheduleStep(loanService),
            new InterestAccrualStep(database, interestService),
            new BalanceSnapshotStep(database),
            new ReconciliationStep(database),
            new OpenNextDayStep(database, calendar));
    }

    public static TfjEngine engine(Database database, PostingService postingService,
                                   BatchInterestAccrualService interestService,
                                   FeeChargingService feeService, LoanService loanService,
                                   BusinessCalendar calendar) {
        return new TfjEngine(database, postingService,
                             steps(database, interestService, feeService, loanService, calendar));
    }
}
