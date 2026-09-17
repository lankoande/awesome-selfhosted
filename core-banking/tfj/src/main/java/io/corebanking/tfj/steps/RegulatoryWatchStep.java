package io.corebanking.tfj.steps;

import io.corebanking.regulatory.ReportingService;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.List;

/**
 * Echeances declaratives : ce que la banque doit au superviseur, et n'a pas envoye.
 *
 * <p><b>Le retard declaratif est en lui-meme un manquement.</b> Il ne se decouvre pas quand le
 * superviseur appelle : il se constate chaque nuit, avec le nom de la declaration, la periode
 * concernee et le nombre de jours de retard. Une declaration oubliee pendant trois mois est une
 * sanction ; la meme, vue le lendemain de l'echeance, est un rattrapage.
 *
 * <p><b>Elle ne bloque pas l'arrete.</b> Un etat non transmis n'empeche pas la banque d'arreter
 * ses comptes — l'inverse serait absurde, puisque c'est precisement l'arrete qui produit les
 * donnees de l'etat. L'etape ne comptabilise rien et ne modifie rien : elle constate.
 */
public final class RegulatoryWatchStep implements TfjStep {

    private final ReportingService reporting;

    public RegulatoryWatchStep(ReportingService reporting) {
        this.reporting = reporting;
    }

    @Override
    public String name() {
        return "REGULATORY_DEADLINES";
    }

    @Override
    public boolean blocking() {
        return false;
    }

    @Override
    public StepResult execute(TfjContext context) {
        ReportingService.Watch watch = reporting.watch(context.legalEntityId(),
                                                       context.businessDate());
        List<String> anomalies = watch.overdue().stream()
            .map(overdue -> overdue.describe(context.businessDate()))
            .toList();
        return new StepResult(watch.declarations(), 0, anomalies);
    }
}
