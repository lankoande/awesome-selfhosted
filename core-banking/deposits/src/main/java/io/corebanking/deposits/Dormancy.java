package io.corebanking.deposits;

import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dormance : un compte sans operation a l'initiative du client depuis le nombre de mois que son
 * produit fixe passe dormant. Il continue de porter interets et frais — l'exclure reviendrait a
 * decider a la place du produit — et se reveille a la premiere operation de son client.
 *
 * <p>Les operations de la banque — interets, commissions, prelevements — ne sont pas des
 * operations du client : elles ne reportent pas la dormance. C'est la source de l'ecriture qui
 * fait la difference, et elle est portee par le journal.
 */
public final class Dormancy {

    private Dormancy() {}

    /** Passe dormants les comptes de l'entite qui le meritent ; rend leurs identifiants. */
    public static List<UUID> detect(Connection c, UUID legalEntityId, LocalDate businessDate,
                                    UUID batchRunId, UUID actorId) {
        Map<String, List<UUID>> byProduct = activeAccountsByProduct(c, legalEntityId, businessDate);
        List<UUID> dormant = new ArrayList<>();
        for (Map.Entry<String, List<UUID>> entry : byProduct.entrySet()) {
            ProductVersion product = ProductCatalog.resolveAt(c, legalEntityId, entry.getKey(),
                                                              businessDate);
            Optional<Integer> months = DepositCatalog.dormancyMonths(product);
            if (months.isEmpty()) {
                continue;
            }
            LocalDate threshold = businessDate.minusMonths(months.get());
            for (UUID accountId : inactiveSince(c, entry.getValue(), threshold)) {
                markDormant(c, accountId, businessDate, batchRunId, actorId);
                dormant.add(accountId);
            }
        }
        return dormant;
    }

    /** Reveille un compte dormant : la premiere operation de son client. */
    public static void reactivate(Connection c, UUID accountId, LocalDate on, UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account SET status = 'ACTIVE' WHERE id = ? AND status = 'DORMANT'")) {
            ps.setObject(1, accountId);
            if (ps.executeUpdate() == 1) {
                AccountLifecycle.event(c, accountId, "REACTIVATED", on, actorId, null,
                                       "operation du client", null);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reactivation du compte " + accountId, e);
        }
    }

    /** Defait les mises en dormance prononcees par un traitement annule. */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account a SET status = 'ACTIVE' FROM account_event e"
            + " WHERE e.account_id = a.id AND e.batch_run_id = ? AND e.kind = 'DORMANT'"
            + "   AND a.status = 'DORMANT'")) {
            ps.setObject(1, batchRunId);
            int restored = ps.executeUpdate();
            try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM account_event WHERE batch_run_id = ? AND kind = 'DORMANT'")) {
                del.setObject(1, batchRunId);
                del.executeUpdate();
            }
            return restored;
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des dormances du traitement", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private static Map<String, List<UUID>> activeAccountsByProduct(Connection c, UUID legalEntityId,
                                                                   LocalDate date) {
        Map<String, List<UUID>> byProduct = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT ON (a.id) a.id, ap.product_code FROM account a"
            + " JOIN account_product ap ON ap.account_id = a.id"
            + " WHERE a.legal_entity_id = ? AND a.account_kind = 'CUSTOMER' AND a.status = 'ACTIVE'"
            + "   AND ap.valid_from <= ? AND (ap.valid_to IS NULL OR ap.valid_to >= ?)"
            + " ORDER BY a.id, ap.valid_from DESC")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, date);
            ps.setObject(3, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byProduct.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                        .add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des comptes actifs", e);
        }
        return byProduct;
    }

    /** Comptes ouverts avant le seuil et sans ecriture de guichet ou de canal depuis. */
    private static List<UUID> inactiveSince(Connection c, List<UUID> accountIds, LocalDate threshold) {
        List<UUID> inactive = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id FROM account a"
            + " WHERE a.id = ANY (?) AND a.opened_at <= ?"
            + "   AND NOT EXISTS ("
            + "       SELECT 1 FROM journal_line l"
            + "         JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date"
            + "        WHERE l.account_id = a.id AND l.booking_date > ? AND e.source = 'ONLINE')"
            + " ORDER BY a.id")) {
            ps.setArray(1, c.createArrayOf("uuid", accountIds.toArray()));
            ps.setObject(2, threshold);
            ps.setObject(3, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    inactive.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche des comptes sans mouvement", e);
        }
        return inactive;
    }

    private static void markDormant(Connection c, UUID accountId, LocalDate on, UUID batchRunId,
                                    UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account SET status = 'DORMANT' WHERE id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise en dormance du compte " + accountId, e);
        }
        AccountLifecycle.event(c, accountId, "DORMANT", on, actorId, null,
                               "aucune operation du client", batchRunId);
    }
}
