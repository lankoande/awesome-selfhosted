package io.corebanking.tfj.steps;

import io.corebanking.deposits.Tills;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Controles prealables.
 *
 * <p>Ils s'executent avant tout calcul, et c'est leur raison d'etre : echouer apres avoir
 * comptabilise des millions de lignes coute une annulation complete, alors qu'echouer avant ne
 * coute rien. Les conditions verifiees ici sont toutes de nature a invalider l'arrete entier.
 */
public final class PreChecksStep implements TfjStep {

    private final Database database;

    public PreChecksStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "PRE_CHECKS";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> anomalies = new ArrayList<>();

        // La partition du mois traite est garantie, pas seulement controlee : sa creation est
        // idempotente et ne prejuge de rien. Le premier arrete d'une base neuve, ou d'une entite
        // dont la date a ete reprise, ne doit pas echouer sur un objet que le systeme sait creer.
        database.inTransaction(c -> {
            SchemaMigrator.ensurePartitions(c, context.businessDate(),
                                            context.businessDate().plusMonths(1));
            return null;
        });

        Optional<String> period = database.inTransaction(c ->
            Entities.periodStatus(c, context.legalEntityId(), context.businessDate()));
        if (period.isEmpty()) {
            anomalies.add("Aucune periode comptable ne couvre le " + context.businessDate()
                          + " : aucune ecriture d'arrete ne pourrait etre imputee. La bascule de "
                          + "journee l'ouvre normalement ; une date comptable reprise a la main "
                          + "l'exige aussi.");
        } else if (!"OPEN".equals(period.get()) && !"REOPENED".equals(period.get())) {
            anomalies.add("La periode comptable couvrant le " + context.businessDate()
                          + " est " + period.get() + " : la rouvrir est une decision comptable, "
                          + "pas un effet de bord de l'arrete.");
        }

        long suspens = database.inTransaction(c -> unbalancedSuspenseAccounts(c, context));
        if (suspens > 0) {
            anomalies.add(suspens + " compte(s) d'attente non solde(s). Leur justification "
                          + "conditionne la sincerite de l'arrete.");
        }

        // L'arrete de caisse precede l'arrete de la banque : une caisse qui a servi dans la
        // journee et n'est pas arretee laisse des especes non confrontees a leur solde.
        List<String> caisses = database.inTransaction(
            c -> Tills.movedAndUnclosed(c, context.legalEntityId(), context.businessDate()));
        if (!caisses.isEmpty()) {
            anomalies.add(caisses.size() + " caisse(s) mouvementee(s) le " + context.businessDate()
                          + " et non arretee(s) : " + caisses + ". L'arrete de caisse precede "
                          + "l'arrete de la banque.");
        }

        return new StepResult(3, 0, anomalies);
    }

    private long unbalancedSuspenseAccounts(java.sql.Connection c, TfjContext context) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM account a"
            + " JOIN account_balance b ON b.account_id = a.id"
            + " WHERE a.legal_entity_id = ? AND a.account_kind = 'SUSPENSE'"
            + " GROUP BY a.id HAVING SUM(b.balance) <> 0")) {
            ps.setObject(1, context.legalEntityId());
            try (ResultSet rs = ps.executeQuery()) {
                long count = 0;
                while (rs.next()) {
                    count++;
                }
                return count;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des comptes d'attente", e);
        }
    }
}
