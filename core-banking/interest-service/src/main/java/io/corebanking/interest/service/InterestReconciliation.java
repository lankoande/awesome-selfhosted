package io.corebanking.interest.service;

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
 * Sous-livre des interets contre le grand livre.
 *
 * <p>Pour chaque compte de courus, la somme des positions qui s'y imputent — impute moins regle —
 * doit egaler son solde. Tout ce qui separe les deux est nomme : une ecriture manuelle sur le
 * compte de courus, un reglement comptabilise sans etre enregistre, un produit dont on a change
 * le compte de courus sans solder la position en cours. Le controle lit les positions, jamais les
 * journees : il coute le nombre de comptes, pas leur historique.
 */
public final class InterestReconciliation implements Reconciliation.Check {

    public static final String CHECK = "SOUS_LIVRE_INTERETS_COURUS";

    @Override
    public List<Reconciliation.Discrepancy> run(Connection c, UUID legalEntityId,
                                                LocalDate businessDate, UUID runId) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT p.accrued_account_id, SUM(p.posted_total - p.settled_total),"
            + "       COALESCE(b.balance, 0)"
            + "  FROM interest_position p"
            + "  JOIN account a ON a.id = p.account_id"
            + "  LEFT JOIN account_balance_agg b ON b.account_id = p.accrued_account_id"
            + " WHERE a.legal_entity_id = ? AND p.accrued_account_id IS NOT NULL"
            + " GROUP BY p.accrued_account_id, b.balance"
            + " HAVING SUM(p.posted_total - p.settled_total) <> COALESCE(b.balance, 0)")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK, rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des positions d'interets", e);
        }
        return discrepancies;
    }
}
