package io.corebanking.tfj.steps;

import io.corebanking.fee.service.FeeChargingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Commissions et frais echus de la journee.
 *
 * <p>Sont percues toutes les commissions dont l'echeance tombe a la date traitee ou avant, et qui
 * n'ont pas encore ete liquidees. Un TFJ de rattrapage facture donc les periodes manquees, chacune
 * au tarif de sa propre echeance.
 *
 * <h2>Pourquoi l'etape est bloquante</h2>
 *
 * <p>Une commission qui n'est pas percue ne laisse aucune trace comptable : l'arrete reste
 * equilibre, les controles de reconciliation passent, et le defaut ne se decouvre qu'a la revue
 * des produits, un trimestre plus tard, sans moyen de rattraper les periodes ecoulees. Un
 * parametrage tarifaire incomplet arrete donc le traitement, comme un parametrage d'interets
 * incomplet l'arrete.
 *
 * <p>Ce que l'etape ne considere pas comme une anomalie : une provision insuffisante. C'est un
 * fait de gestion courant, traite selon la politique du produit — abandon, report, forcage. Le
 * signaler ici arreterait le TFJ de la banque entiere parce qu'un client est a decouvert. Le
 * constat reste consigne, liquidation par liquidation, dans le registre des commissions.
 */
public final class FeeChargingStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    /** Meme raison que pour les interets : borner la memoire du lot, sans perdre l'agregation. */
    private static final int CHUNK_SIZE = Integer.getInteger("tfj.fees.chunk", 5_000);

    private final Database database;
    private final FeeChargingService feeService;

    public FeeChargingStep(Database database, FeeChargingService feeService) {
        this.database = database;
        this.feeService = feeService;
    }

    @Override
    public String name() {
        return "FEE_CHARGING";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> accounts = Portfolio.accountsWithProduct(database, context);
        if (accounts.isEmpty()) {
            return StepResult.none();
        }

        List<String> anomalies = new ArrayList<>();
        long charged = 0;

        for (int start = 0; start < accounts.size(); start += CHUNK_SIZE) {
            List<UUID> chunk = accounts.subList(start,
                                                Math.min(start + CHUNK_SIZE, accounts.size()));
            FeeChargingService.Outcome outcome = feeService.chargeDue(
                context.legalEntityId(), chunk, context.businessDate(), context.actorId(),
                context.runId());

            charged += outcome.charged() + outcome.recovered();
            outcome.anomalies().stream()
                .limit(Math.max(0, MAX_REPORTED - anomalies.size()))
                .forEach(anomalies::add);
        }
        if (anomalies.size() >= MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage tarifaire et relancer.");
        }
        return new StepResult(accounts.size(), charged, anomalies);
    }
}
