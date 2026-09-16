package io.corebanking.deposits;

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
 * Rapprochement du sous-livre des depots a terme.
 *
 * <p>Ce que les contrats disent devoir en interets non regles doit etre exactement ce que le
 * compte de courus porte. Un ecart designe l'une de deux choses, et les deux comptent : des
 * interets constates que rien ne reclamera — la banque provisionne une dette qui n'existe pas —,
 * ou une charge oubliee, et le resultat est alors surevalue.
 *
 * <p>Le controle porte compte par compte de courus : plusieurs produits peuvent partager le leur,
 * et c'est la somme des contrats qui doit s'y retrouver.
 */
public final class TermDepositReconciliation implements Reconciliation.Check {

    public static final String CHECK = "SOUS_LIVRE_DEPOTS_A_TERME";

    @Override
    public List<Reconciliation.Discrepancy> run(Connection c, UUID legalEntityId,
                                                LocalDate businessDate, UUID runId) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT d.accrued_account_id, SUM(d.accrued_total - d.settled_total),"
            + "       COALESCE(b.balance, 0)"
            + "  FROM term_deposit d"
            + "  LEFT JOIN account_balance_agg b ON b.account_id = d.accrued_account_id"
            + " WHERE d.legal_entity_id = ?"
            + " GROUP BY d.accrued_account_id, b.balance"
            + " HAVING SUM(d.accrued_total - d.settled_total) <> COALESCE(b.balance, 0)")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK, rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des depots a terme", e);
        }
        return discrepancies;
    }
}
