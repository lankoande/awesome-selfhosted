package io.corebanking.tfj;

import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.steps.BalanceSnapshotStep;
import io.corebanking.tfj.steps.InterestAccrualStep;
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
 *   <li><b>Les interets avant le cliche des soldes.</b> Le cliche doit refleter la journee arretee,
 *       interets compris — sinon le solde fige et le solde rejoue divergeront des le lendemain.</li>
 *   <li><b>La reconciliation avant la bascule.</b> C'est tout le mecanisme : tant que les controles
 *       ne sont pas verts, la journee ne bascule pas, et le systeme refuse de travailler sur la
 *       suivante.</li>
 * </ul>
 *
 * <p>La sequence est volontairement courte. Les etapes que le dossier prevoit et qui manquent
 * encore — reconstruction des soldes en date de valeur, echeances de credit, penalites,
 * commissions, classification, provisionnement, revalorisation de change, dormance, expiration
 * des blocages, revue KYC — s'inserent ici sans toucher au moteur.
 */
public final class StandardTfj {

    private StandardTfj() {}

    public static List<TfjStep> steps(Database database,
                                      BatchInterestAccrualService interestService) {
        return List.of(
            new PreChecksStep(database),
            new InterestAccrualStep(database, interestService),
            new BalanceSnapshotStep(database),
            new ReconciliationStep(database),
            new OpenNextDayStep(database));
    }

    public static TfjEngine engine(Database database, PostingService postingService,
                                   BatchInterestAccrualService interestService) {
        return new TfjEngine(database, postingService, steps(database, interestService));
    }
}
