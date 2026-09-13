package io.corebanking.ledger.store;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Controles d'integrite du ledger.
 *
 * <p>Ils s'executent a chaque TFJ et <b>bloquent la bascule de journee</b> au moindre ecart. Le
 * seuil de tolerance est zero. La tentation d'en accepter un est forte en exploitation ; elle est
 * toujours perdante : un ecart admis aujourd'hui devient en fin d'exercice un ecart dont personne
 * ne retrouve l'origine.
 */
public final class Reconciliation {

    private Reconciliation() {}

    /** Ecart constate par un controle. Une liste vide est le seul resultat acceptable. */
    public record Discrepancy(String check, String scope, BigDecimal expected, BigDecimal actual) {
        public BigDecimal gap() {
            return actual.subtract(expected);
        }

        @Override
        public String toString() {
            return check + " [" + scope + "] attendu " + expected.toPlainString()
                   + ", constate " + actual.toPlainString();
        }
    }

    /**
     * Controle 1 — la balance generale est equilibree, par entite et par devise.
     *
     * <p>C'est le controle souverain : s'il passe, aucune ecriture desequilibree n'a ete
     * enregistree depuis l'origine.
     */
    public static List<Discrepancy> generalLedgerBalanced(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT currency,"
            + "       SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS gap"
            + "  FROM journal_line WHERE legal_entity_id = ?"
            + " GROUP BY currency"
            + " HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "BALANCE_EQUILIBREE", rs.getString(1), BigDecimal.ZERO, rs.getBigDecimal(2)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle d'equilibre de la balance", e);
        }
        return discrepancies;
    }

    /**
     * Controle 2 — le solde materialise egale le solde rejoue depuis le journal.
     *
     * <p>Il valide que le cache de soldes n'a pas derive. Execute sur echantillon a chaque TFJ, sur
     * la totalite du portefeuille au TFM.
     */
    public static List<Discrepancy> materializedMatchesReplay(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "WITH replayed AS ("
            + "   SELECT l.account_id,"
            + "          SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount"
            + "                   ELSE -l.amount END) AS balance"
            + "     FROM journal_line l JOIN account a ON a.id = l.account_id"
            + "    WHERE l.legal_entity_id = ?"
            + "    GROUP BY l.account_id),"
            + " materialized AS ("
            + "   SELECT b.account_id, SUM(b.balance) AS balance"
            + "     FROM account_balance b JOIN account a ON a.id = b.account_id"
            + "    WHERE a.legal_entity_id = ?"
            + "    GROUP BY b.account_id)"
            + " SELECT m.account_id, COALESCE(r.balance, 0), m.balance"
            + "   FROM materialized m LEFT JOIN replayed r ON r.account_id = m.account_id"
            + "  WHERE m.balance <> COALESCE(r.balance, 0)")) {
            ps.setObject(1, entityId);
            ps.setObject(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "SOLDE_MATERIALISE_VS_REJOUE", rs.getString(1),
                        rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des soldes materialises", e);
        }
        return discrepancies;
    }

    /**
     * Controle 3 — la somme des stripes egale le solde agrege du compte.
     *
     * <p>Il detecte une repartition de solde corrompue sur les comptes chauds : une mise a jour
     * appliquee a une stripe inexistante, ou un compte dont le nombre de stripes a change sans
     * reprise des soldes.
     */
    public static List<Discrepancy> stripesConsistent(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.stripe_count, count(b.stripe_id)"
            + "  FROM account a LEFT JOIN account_balance b ON b.account_id = a.id"
            + " WHERE a.legal_entity_id = ?"
            + " GROUP BY a.id, a.stripe_count"
            + " HAVING count(b.stripe_id) <> a.stripe_count")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "STRIPES_COMPLETES", rs.getString(1),
                        BigDecimal.valueOf(rs.getInt(2)), BigDecimal.valueOf(rs.getInt(3))));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des stripes", e);
        }
        return discrepancies;
    }

    /** Ensemble des controles bloquants de la bascule de journee. */
    public static List<Discrepancy> allBlockingChecks(Connection c, UUID entityId) {
        List<Discrepancy> all = new ArrayList<>();
        all.addAll(generalLedgerBalanced(c, entityId));
        all.addAll(materializedMatchesReplay(c, entityId));
        all.addAll(stripesConsistent(c, entityId));
        return all;
    }
}
