package io.corebanking.fee.service;

import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.Reconciliation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sous-livre des commissions contre le journal, pour le traitement en cours.
 *
 * <p>Chaque commission percue par le traitement reference l'ecriture qui l'a comptabilisee ; le
 * total debite par cette ecriture doit etre exactement le total de la commission — net et taxe.
 * Une commission enregistree sans ecriture, une ecriture contre-passee dont la commission reste
 * percue, un montant qui differe : chacun est nomme. Le controle ne lit que la journee du
 * traitement, par sa partition.
 */
public final class FeeReconciliation implements Reconciliation.Check {

    public static final String CHECK = "SOUS_LIVRE_COMMISSIONS";

    @Override
    public List<Reconciliation.Discrepancy> run(Connection c, UUID legalEntityId,
                                                LocalDate businessDate, UUID runId) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        if (runId == null) {
            return discrepancies;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT f.account_id, f.fee_code, f.period_end, f.total_amount,"
            + "       COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount END), 0)"
            + "  FROM fee_charge f"
            + "  LEFT JOIN journal_line l ON l.entry_id = f.entry_id AND l.booking_date = ?"
            + "  LEFT JOIN journal_reversal r ON r.reversed_entry_id = f.entry_id"
            + " WHERE f.legal_entity_id = ? AND f.batch_run_id = ?"
            + "   AND f.outcome IN ('COLLECTED','FORCED') AND r.reversed_entry_id IS NULL"
            + " GROUP BY f.id, f.account_id, f.fee_code, f.period_end, f.total_amount"
            + " HAVING f.total_amount <> COALESCE(SUM(CASE WHEN l.direction = 'DEBIT'"
            + "                                             THEN l.amount END), 0)")) {
            ps.setObject(1, businessDate);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK, rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des commissions du traitement", e);
        }
        return discrepancies;
    }
}
