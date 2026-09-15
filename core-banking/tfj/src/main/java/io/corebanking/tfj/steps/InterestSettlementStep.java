package io.corebanking.tfj.steps;

import io.corebanking.interest.service.CatalogTermsProvider;
import io.corebanking.interest.service.InterestPositions;
import io.corebanking.interest.service.InterestSettlementService;
import io.corebanking.interest.service.InterestTerms;
import io.corebanking.interest.service.SettlementCalendar;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.store.Database;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reglement des interets courus : capitalisation des interets crediteurs, arrete des agios.
 *
 * <p>Apres le calcul des interets de la journee, toute position dont une fin de periode de
 * reglement est atteinte et pas encore reglee l'est maintenant — jusqu'a cette fin de periode,
 * meme si l'arrete tourne quelques jours apres elle. Le compte de courus est repris, le client
 * credite net de retenue ou debite taxe comprise.
 *
 * <p>L'etape ne parcourt que les positions qui peuvent avoir quelque chose a regler : calculees,
 * et pas reglees jusqu'a la derniere fin de mois. Le lendemain d'une fin de mois, c'est tout le
 * portefeuille pour une nuit ; les autres nuits, presque rien.
 */
public final class InterestSettlementStep implements TfjStep {

    private static final int MAX_REPORTED = 20;

    private final Database database;
    private final InterestSettlementService settlementService;

    public InterestSettlementStep(Database database, InterestSettlementService settlementService) {
        this.database = database;
        this.settlementService = settlementService;
    }

    @Override
    public String name() {
        return "INTEREST_SETTLEMENT";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        LocalDate monthEnd = SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.MONTHLY,
                                                                        context.businessDate());
        List<InterestPositions.Key> candidates = database.inTransaction(
            c -> InterestPositions.settlementCandidates(c, context.legalEntityId(), monthEnd));
        if (candidates.isEmpty()) {
            return StepResult.none();
        }
        List<UUID> accounts = candidates.stream().map(InterestPositions.Key::accountId)
            .distinct().toList();
        CatalogTermsProvider provider = CatalogTermsProvider.forAccounts(
            database, context.legalEntityId(), accounts, context.businessDate());

        List<String> anomalies = new ArrayList<>();
        long settled = 0;
        for (InterestPositions.Key key : candidates) {
            try {
                Optional<InterestTerms> terms = provider.termsForSide(
                    key.accountId(), context.businessDate(), key.side());
                if (terms.isEmpty()) {
                    continue;              // le produit ne remunere plus ce cote : rien a regler
                }
                if (settlementService.settleOne(context.legalEntityId(), key.accountId(),
                                                context.businessDate(), terms.get(),
                                                context.businessDate(), context.actorId(),
                                                context.runId()).isPresent()) {
                    settled++;
                }
            } catch (RuntimeException e) {
                if (anomalies.size() < MAX_REPORTED) {
                    anomalies.add("Compte " + key.accountId() + " (" + key.side() + ") non regle : "
                                  + e.getMessage());
                }
            }
        }
        if (anomalies.size() >= MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage et relancer.");
        }
        return new StepResult(candidates.size(), settled, anomalies);
    }
}
