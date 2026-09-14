package io.corebanking.tfj.steps;

import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.interest.service.CatalogTermsProvider;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Interets courus de la journee.
 *
 * <p>Chaque compte rattache a un produit est remunere jusqu'a la date de valeur traitee, avec le
 * bareme en vigueur ce jour-la et non celui du jour du traitement. Un TFJ de rattrapage produit
 * donc exactement ce que le TFJ du jour aurait produit.
 *
 * <h2>Un compte sans produit resolvable est une anomalie, pas un compte a ignorer</h2>
 *
 * <p>Passer silencieusement un compte dont le parametrage ne se resout pas revient a ne pas le
 * remunerer : l'arrete reste equilibre, les controles passent, et le defaut n'apparait qu'a la
 * reclamation du client, des mois plus tard. L'etape recense donc tous les comptes concernes avant
 * d'echouer — signaler le premier obligerait a autant de TFJ que de comptes mal parametres.
 */
public final class InterestAccrualStep implements TfjStep {

    /** Au-dela, la liste devient illisible ; le compte exact reste dans les compteurs. */
    private static final int MAX_REPORTED = 20;

    /**
     * Taille d'un lot.
     *
     * <p>Le calcul ensembliste charge en memoire les mouvements des comptes qu'il traite. Sur un
     * portefeuille entier, tout charger d'un coup epuiserait la memoire bien avant la fin — le
     * decoupage n'est pas un reglage de confort, c'est ce qui rend la methode utilisable a
     * l'echelle reelle. Il ne coute rien : chaque lot conserve le benefice de l'agregation, et
     * deux millions de comptes produisent quelques centaines d'ecritures au lieu de deux millions.
     */
    private static final int CHUNK_SIZE = Integer.getInteger("tfj.accrual.chunk", 5_000);

    private final Database database;
    private final BatchInterestAccrualService batchService;

    public InterestAccrualStep(Database database, BatchInterestAccrualService batchService) {
        this.database = database;
        this.batchService = batchService;
    }

    @Override
    public String name() {
        return "INTEREST_ACCRUAL";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<UUID> accounts = accountsWithProduct(context);
        if (accounts.isEmpty()) {
            return StepResult.none();
        }

        var provider = CatalogTermsProvider.forAccounts(
            database, context.legalEntityId(), accounts, context.businessDate());

        List<String> anomalies = new ArrayList<>();
        long accrued = 0;

        for (int start = 0; start < accounts.size(); start += CHUNK_SIZE) {
            List<UUID> chunk = accounts.subList(start,
                                                Math.min(start + CHUNK_SIZE, accounts.size()));
            var outcome = batchService.accrue(
                context.legalEntityId(), chunk, context.businessDate(), provider,
                context.businessDate(), context.actorId(), context.runId(),
                BatchInterestAccrualService.chunkTag(chunk));

            accrued += outcome.accountsAccrued();
            outcome.anomalies().stream()
                .limit(Math.max(0, MAX_REPORTED - anomalies.size()))
                .forEach(anomalies::add);
        }
        if (anomalies.size() >= MAX_REPORTED) {
            anomalies.add("... liste tronquee ; corriger le parametrage et relancer.");
        }
        return new StepResult(accounts.size(), accrued, anomalies);
    }

    private List<UUID> accountsWithProduct(TfjContext context) {
        return database.inTransaction(c -> {
            List<UUID> accounts = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT a.id FROM account a"
                + " JOIN account_product ap ON ap.account_id = a.id"
                + " WHERE a.legal_entity_id = ? AND a.status IN ('ACTIVE','DORMANT')"
                + "   AND ap.valid_from <= ? AND (ap.valid_to IS NULL OR ap.valid_to >= ?)"
                + " ORDER BY a.id")) {
                ps.setObject(1, context.legalEntityId());
                ps.setObject(2, context.businessDate());
                ps.setObject(3, context.businessDate());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        accounts.add(rs.getObject(1, UUID.class));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recensement des comptes remuneres", e);
            }
            return accounts;
        });
    }
}
