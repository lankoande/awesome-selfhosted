package io.corebanking.tfj;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.fee.service.FeeChargingService;
import io.corebanking.fee.service.FeeReconciliation;
import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.interest.service.InterestReconciliation;
import io.corebanking.interest.service.InterestSettlementService;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.loan.service.LoanClassificationService;
import io.corebanking.loan.service.LoanInterestAccrualService;
import io.corebanking.loan.service.LoanLateChargesService;
import io.corebanking.loan.service.LoanMobilisationService;
import io.corebanking.loan.service.LoanReconciliation;
import io.corebanking.loan.service.LoanService;
import io.corebanking.tfj.steps.BalanceSnapshotStep;
import io.corebanking.tfj.steps.FeeChargingStep;
import io.corebanking.tfj.steps.InterestAccrualStep;
import io.corebanking.tfj.steps.InterestSettlementStep;
import io.corebanking.tfj.steps.LoanClassificationStep;
import io.corebanking.tfj.steps.LoanClosureStep;
import io.corebanking.tfj.steps.LoanInterestAccrualStep;
import io.corebanking.tfj.steps.LoanLateChargesStep;
import io.corebanking.tfj.steps.LoanMobilisationStep;
import io.corebanking.tfj.steps.LoanScheduleStep;
import io.corebanking.tfj.steps.OpenNextDayStep;
import io.corebanking.tfj.steps.PreChecksStep;
import io.corebanking.tfj.steps.ReconciliationStep;
import java.util.List;

/**
 * Sequence standard du TFJ.
 *
 * <p>L'ordre n'est pas arbitraire, et ces contraintes le fixent :
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
 *   <li><b>Les interets courus des credits juste apres l'exigibilite.</b> L'echeance reclamee
 *       aujourd'hui a repris ses courus ; son etalement doit etre complete dans le meme arrete,
 *       et avant la classification, qui commande le compte de produit des courus du
 *       lendemain.</li>
 *   <li><b>Les charges de retard apres le prelevement.</b> Un compte provisionne a deja ete
 *       debite de son echeance et n'a rien a payer au titre du retard. L'ordre inverse
 *       penaliserait un client qui paie.</li>
 *   <li><b>La classification en dernier des traitements de credit.</b> Elle classe sur l'etat des
 *       impayes tel qu'il ressort de la journee, et sa decision commande la constatation des
 *       interets du lendemain. L'inverse serait circulaire : suspendre les interets du jour
 *       dependrait de la classe qu'on est en train d'etablir.</li>
 *   <li><b>La cloture des credits apres la classification.</b> C'est la classification qui
 *       reprend la provision d'un encours devenu nul ; un credit clos avant elle emporterait sa
 *       provision hors du portefeuille classe, et elle ne serait jamais reprise.</li>
 *   <li><b>Le reglement des interets apres leur calcul.</b> Ce qui est capitalise ou preleve est
 *       ce qui a ete calcule jusqu'a la fin de periode ; le calcul du jour doit etre fait.</li>
 *   <li><b>Les interets avant le cliche des soldes.</b> Le cliche doit refleter la journee arretee,
 *       interets compris — sinon le solde fige et le solde rejoue divergeront des le lendemain.</li>
 *   <li><b>La reconciliation avant la bascule.</b> C'est tout le mecanisme : tant que les controles
 *       ne sont pas verts, la journee ne bascule pas, et le systeme refuse de travailler sur la
 *       suivante.</li>
 * </ul>
 *
 * <p>Les etapes que le dossier prevoit et qui manquent encore — revalorisation de change,
 * dormance, expiration des blocages, revue KYC — s'inserent ici sans toucher au moteur.
 */
public final class StandardTfj {

    private StandardTfj() {}

    /** Rapprochements de sous-livres executes a chaque arrete, quotidien comme mensuel. */
    public static List<Reconciliation.Check> subLedgerChecks() {
        return List.of(new InterestReconciliation(), new LoanReconciliation(),
                       new FeeReconciliation());
    }

    public static List<TfjStep> steps(Database database, PostingService postingService,
                                      BatchInterestAccrualService interestService,
                                      FeeChargingService feeService, LoanService loanService,
                                      LoanMobilisationService mobilisationService,
                                      LoanLateChargesService lateService,
                                      LoanClassificationService classificationService,
                                      BusinessCalendar calendar) {
        return List.of(
            new PreChecksStep(database),
            new FeeChargingStep(database, feeService),
            new LoanMobilisationStep(mobilisationService),
            new LoanScheduleStep(loanService),
            new LoanInterestAccrualStep(new LoanInterestAccrualService(database, postingService)),
            new LoanLateChargesStep(lateService),
            new LoanClassificationStep(classificationService),
            new LoanClosureStep(loanService),
            new InterestAccrualStep(database, interestService),
            new InterestSettlementStep(database,
                                       new InterestSettlementService(database, postingService)),
            new BalanceSnapshotStep(database),
            new ReconciliationStep(database, subLedgerChecks()),
            new OpenNextDayStep(database, calendar));
    }

    public static TfjEngine engine(Database database, PostingService postingService,
                                   BatchInterestAccrualService interestService,
                                   FeeChargingService feeService, LoanService loanService,
                                   LoanMobilisationService mobilisationService,
                                   LoanLateChargesService lateService,
                                   LoanClassificationService classificationService,
                                   BusinessCalendar calendar) {
        return new TfjEngine(database, postingService,
                             steps(database, postingService, interestService, feeService,
                                   loanService, mobilisationService, lateService,
                                   classificationService, calendar));
    }
}
