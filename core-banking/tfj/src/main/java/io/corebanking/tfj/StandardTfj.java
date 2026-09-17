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
import io.corebanking.tfj.steps.DirectDebitsStep;
import io.corebanking.tfj.steps.DocumentExpiryStep;
import io.corebanking.tfj.steps.StandingOrdersStep;
import io.corebanking.tfj.steps.OfferExpiryStep;
import io.corebanking.tfj.steps.DormancyStep;
import io.corebanking.tfj.steps.FxRatesStep;
import io.corebanking.tfj.steps.FxRevaluationStep;
import io.corebanking.tfj.steps.AmlMonitoringStep;
import io.corebanking.tfj.steps.SuspenseReviewStep;
import io.corebanking.tfj.steps.TermDepositAccrualStep;
import io.corebanking.tfj.steps.TermDepositMaturityStep;
import io.corebanking.tfj.steps.FeeChargingStep;
import io.corebanking.tfj.steps.HoldExpiryStep;
import io.corebanking.tfj.steps.InterestAccrualStep;
import io.corebanking.tfj.steps.InterestSettlementStep;
import io.corebanking.tfj.steps.KycReviewStep;
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
 *   <li><b>L'expiration des blocages de montant avant tout prelevement.</b> Un blocage qui expire
 *       ce jour libere du disponible ; commissions et echeances se prelevent sur le disponible de
 *       la journee arretee, pas sur celui de la veille.</li>
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
 *   <li><b>Les cours de cloture avant tout calcul.</b> Une journee qui comptabiliserait des
 *       interets en devise, puis decouvrirait a la revalorisation qu'il lui manque un cours,
 *       aurait a etre annulee en entier ; le cours manquant se cote en une minute.</li>
 *   <li><b>La revalorisation apres tous les traitements comptables et avant le cliche.</b> Elle
 *       revalorise ce que la journee a laisse — une ecriture posterieure vaudrait a son cours
 *       propre —, et le cliche doit refleter la journee arretee, revalorisation comprise.</li>
 *   <li><b>Les prelevements a l'echeance apres l'expiration des blocages, avant les commissions
 *       et les echeances de credit.</b> Ils s'executent sur le disponible de la journee, blocages
 *       expires compris ; le prelevement est un engagement du client envers un tiers, pris a
 *       date, et son rejet lui est opposable chez le creancier, quand commission et echeance de
 *       credit ont leur regime de report et de retard.</li>
 *   <li><b>Les pieces expirees avec la revue de connaissance client.</b> Une piece qui expire
 *       n'arrete rien ce soir-la : elle est constatee, et le dossier cesse d'etre ouvrable
 *       demain. Comme la revue, elle ne comptabilise rien et se defait avec l'arrete.</li>
 *   <li><b>La revue des suspens avec la dormance et la revue de connaissance client.</b> Elle
 *       ne comptabilise rien et ne bloque pas : elle rend la liste de travail du lendemain, par
 *       nature, avec le plus ancien et son responsable. Ce qui bloque — un compte d'attente en
 *       retard — est dit par les controles prealables, avant tout calcul.</li>
 *   <li><b>Dormance et revue de connaissance client apres les traitements comptables.</b> Elles ne
 *       comptabilisent rien et ne bloquent pas la journee : un dossier de revue en retard ne doit
 *       pas empecher la banque d'arreter ses comptes. Elles sont defaites avec l'arrete.</li>
 *   <li><b>Les interets des depots a terme avant leur echeance, et les deux avant les interets
 *       sur depots.</b> Ce qui est servi au client au terme est ce qui a ete constate, journee du
 *       terme comprise ; et les interets qu'un terme verse sur un compte courant entrent dans le
 *       solde sur lequel ce compte est remunere le meme jour. L'ordre inverse remunererait un
 *       solde que le client n'a pas encore.</li>
 *   <li><b>La surveillance LCB-FT apres les traitements comptables.</b> Ce qu'elle regarde est
 *       la journee telle qu'elle a ete arretee, prelevements et echeances compris ; un scenario
 *       qui tournerait avant ignorerait la moitie des mouvements du jour. Comme les autres
 *       revues, elle ne comptabilise rien et ne bloque pas : un client suspect n'est pas une
 *       panne de la banque.</li>
 *   <li><b>La reconciliation avant la bascule.</b> C'est tout le mecanisme : tant que les controles
 *       ne sont pas verts, la journee ne bascule pas, et le systeme refuse de travailler sur la
 *       suivante.</li>
 * </ul>
 *
 * <p>L'etape que le dossier prevoit et qui manque encore — la revalorisation de change — s'insere
 * ici sans toucher au moteur.
 */
public final class StandardTfj {

    private StandardTfj() {}

    /** Rapprochements de sous-livres executes a chaque arrete, quotidien comme mensuel. */
    public static List<Reconciliation.Check> subLedgerChecks() {
        return List.of(new InterestReconciliation(), new LoanReconciliation(),
                       new FeeReconciliation(),
                       new io.corebanking.deposits.TermDepositReconciliation());
    }

    public static List<TfjStep> steps(Database database, PostingService postingService,
                                      BatchInterestAccrualService interestService,
                                      FeeChargingService feeService, LoanService loanService,
                                      LoanMobilisationService mobilisationService,
                                      LoanLateChargesService lateService,
                                      LoanClassificationService classificationService,
                                      BusinessCalendar calendar) {
        io.corebanking.deposits.TermDepositService termDeposits =
            new io.corebanking.deposits.TermDepositService(database, postingService);
        return List.of(
            new PreChecksStep(database, calendar),
            new FxRatesStep(database),
            new HoldExpiryStep(database),
            new DirectDebitsStep(database,
                                 new io.corebanking.deposits.DirectDebitService(database,
                                                                                 postingService)),
            new StandingOrdersStep(database,
                                   new io.corebanking.deposits.StandingOrderService(
                                       database, postingService)),
            new FeeChargingStep(database, feeService),
            new LoanMobilisationStep(mobilisationService),
            new LoanScheduleStep(loanService),
            new LoanInterestAccrualStep(new LoanInterestAccrualService(database, postingService)),
            new LoanLateChargesStep(lateService),
            new LoanClassificationStep(classificationService),
            new LoanClosureStep(loanService),
            new TermDepositAccrualStep(termDeposits),
            new TermDepositMaturityStep(database, termDeposits),
            new InterestAccrualStep(database, interestService),
            new InterestSettlementStep(database,
                                       new InterestSettlementService(database, postingService)),
            new FxRevaluationStep(new io.corebanking.ledger.store.FxRevaluation(database,
                                                                                  postingService)),
            new DormancyStep(database),
            new KycReviewStep(database),
            new DocumentExpiryStep(database),
            new OfferExpiryStep(database),
            new SuspenseReviewStep(database, calendar),
            new AmlMonitoringStep(
                new io.corebanking.compliance.MonitoringService(database)),
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
