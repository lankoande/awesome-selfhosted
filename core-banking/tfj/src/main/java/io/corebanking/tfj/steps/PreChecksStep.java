package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

        if (!database.inTransaction(c ->
                Entities.isPeriodOpen(c, context.legalEntityId(), context.businessDate()))) {
            anomalies.add("Aucune periode comptable ouverte au " + context.businessDate()
                          + " : aucune ecriture d'arrete ne pourrait etre imputee.");
        }

        long suspens = database.inTransaction(c -> unbalancedSuspenseAccounts(c, context));
        if (suspens > 0) {
            anomalies.add(suspens + " compte(s) d'attente non solde(s). Leur justification "
                          + "conditionne la sincerite de l'arrete.");
        }

        return new StepResult(2, 0, anomalies);
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
