package io.corebanking.compliance;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alertes LCB-FT : la file de travail de la conformite.
 *
 * <p><b>Une alerte n'est pas une sanction.</b> Elle constate, elle n'empeche rien : bloquer un
 * compte sur un compteur statistique priverait un client de son argent sur une presomption, et
 * la banque ne saurait meme pas dire laquelle. Seul le filtrage bloque — operer avec une personne
 * listee est l'infraction elle-meme, pas un soupcon.
 *
 * <p><b>Une alerte porte ses pieces.</b> Les operations qui l'ont declenchee sont enregistrees
 * avec elle : sans elles, l'instruction se ferait sur une intuition, et la decision de classer ne
 * se controlerait pas.
 *
 * <p><b>Une alerte est secrete.</b> Rien de ce qui est ici ne remonte au dossier client ni a
 * aucune lecture d'agence : informer la personne surveillee est un delit.
 */
public final class AmlAlerts {

    private AmlAlerts() {}

    public enum Origin { SCREENING, MONITORING }

    /** Une operation qui a declenche l'alerte. */
    public record Item(UUID entryId, LocalDate bookingDate, UUID accountId, String direction,
                       Money amount) {}

    public record Alert(UUID id, UUID legalEntityId, UUID partyId, String scenarioCode,
                        Origin origin, LocalDate raisedOn, String detail, Money amount,
                        String status, UUID assignedTo, LocalDate closedOn, String closureReason,
                        UUID closedBy, UUID reportId, List<Item> items) {

        public boolean open() {
            return "OPEN".equals(status) || "UNDER_REVIEW".equals(status);
        }
    }

    /** Alerte refusee : un etat qui ne s'y prete pas. */
    public static class AlertStateException extends IllegalStateException {
        public AlertStateException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ levee

    /**
     * Leve une alerte.
     *
     * @param scenarioCode le scenario qui l'a levee, nul pour une correspondance de filtrage
     * @param runId l'arrete qui l'a levee, nul en ligne — c'est lui qui la defait si l'arrete
     *     est annule : une alerte issue d'une journee effacee n'a plus de fait derriere elle
     */
    public static UUID raise(Connection c, UUID legalEntityId, UUID partyId, String scenarioCode,
                             Origin origin, LocalDate on, String detail, Money amount,
                             List<Item> items, UUID runId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO aml_alert(id, legal_entity_id, party_id, scenario_code, origin,"
            + " raised_on, detail, amount, currency, batch_run_id) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, partyId);
            ps.setString(4, scenarioCode);
            ps.setString(5, origin.name());
            ps.setObject(6, on);
            ps.setString(7, detail);
            if (amount == null) {
                ps.setNull(8, Types.NUMERIC);
                ps.setNull(9, Types.CHAR);
            } else {
                ps.setBigDecimal(8, amount.amount());
                ps.setString(9, amount.currency().code());
            }
            ps.setObject(10, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Levee d'une alerte LCB-FT", e);
        }
        if (items != null && !items.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO aml_alert_item(alert_id, entry_id, booking_date, account_id,"
                + " direction, amount, currency) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT DO NOTHING")) {
                for (Item item : items) {
                    ps.setObject(1, id);
                    ps.setObject(2, item.entryId());
                    ps.setObject(3, item.bookingDate());
                    ps.setObject(4, item.accountId());
                    ps.setString(5, item.direction());
                    ps.setBigDecimal(6, item.amount().amount());
                    ps.setString(7, item.amount().currency().code());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new LedgerStoreException("Pieces de l'alerte LCB-FT", e);
            }
        }
        return id;
    }

    /**
     * Une alerte du meme scenario pese-t-elle deja sur ce tiers dans la fenetre ?
     *
     * <p>Un scenario qui compte sur trente jours reclamerait le meme fait trente fois : la
     * conformite noierait le vrai signal sous le sien. Une alerte deja levee sur la fenetre suffit
     * — qu'elle soit ouverte ou classee : la classer est une decision, la reposer le lendemain la
     * defait.
     */
    public static boolean alreadyRaised(Connection c, UUID partyId, String scenarioCode,
                                        LocalDate from, LocalDate to) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM aml_alert WHERE party_id = ? AND scenario_code = ?"
            + "   AND raised_on BETWEEN ? AND ? LIMIT 1")) {
            ps.setObject(1, partyId);
            ps.setString(2, scenarioCode);
            ps.setObject(3, from);
            ps.setObject(4, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Alertes deja levees", e);
        }
    }

    // ------------------------------------------------------------------ instruction

    /**
     * Verrouille des alertes pour la duree de la transaction, dans un ordre stable.
     *
     * <p>L'ordre importe : deux transactions qui verrouilleraient les memes alertes chacune dans
     * son ordre s'attendraient l'une l'autre. Trie, le verrouillage ne peut pas boucler.
     */
    static void lock(Connection c, List<UUID> alertIds) {
        Object[] sorted = alertIds.stream().sorted().toArray();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM aml_alert WHERE id = ANY (?) ORDER BY id FOR UPDATE")) {
            ps.setArray(1, c.createArrayOf("uuid", sorted));
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou sur les alertes", e);
        }
    }

    /** Prend l'alerte en charge : elle passe a l'instruction, et on sait qui l'instruit. */
    public static Alert assign(Connection c, UUID alertId, UUID toWhom) {
        lock(c, List.of(alertId));
        Alert alert = require(c, alertId);
        if (!alert.open()) {
            throw new AlertStateException("L'alerte " + alertId + " est " + alert.status()
                + " : elle ne se reprend pas en instruction");
        }
        update(c, "UPDATE aml_alert SET status = 'UNDER_REVIEW', assigned_to = ? WHERE id = ?",
               toWhom, alertId);
        return require(c, alertId);
    }

    /**
     * Classe l'alerte, avec son motif.
     *
     * <p>Le motif n'est pas une politesse : « classee sans suite » sans raison ecrite ne se
     * controle pas, et c'est precisement ce que l'inspection vient lire.
     */
    public static Alert close(Connection c, UUID alertId, LocalDate on, String reason,
                              UUID closedBy) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Le classement d'une alerte porte son motif");
        }
        lock(c, List.of(alertId));
        Alert alert = require(c, alertId);
        if (!alert.open()) {
            throw new AlertStateException("L'alerte " + alertId + " est deja " + alert.status());
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE aml_alert SET status = 'CLOSED', closed_on = ?, closure_reason = ?,"
            + " closed_by = ? WHERE id = ?")) {
            ps.setObject(1, on);
            ps.setString(2, reason.trim());
            ps.setObject(3, closedBy);
            ps.setObject(4, alertId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Classement de l'alerte", e);
        }
        return require(c, alertId);
    }

    // ------------------------------------------------------------------ annulation d'un arrete

    /**
     * Efface les alertes levees par un arrete annule.
     *
     * <p>Une alerte nait d'operations ; si la journee qui les a produites est defaite, elle n'a
     * plus de fait derriere elle. Sauf une : celle qu'on a deja instruite, ou declaree. Defaire le
     * travail de la conformite parce qu'un arrete est rejoue serait pire que le garder.
     */
    public static int cancelRun(Connection c, UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM aml_alert WHERE batch_run_id = ? AND status = 'OPEN'"
            + "   AND assigned_to IS NULL")) {
            ps.setObject(1, runId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des alertes du traitement", e);
        }
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT a.id, a.legal_entity_id, a.party_id, a.scenario_code, a.origin, a.raised_on,"
        + " a.detail, a.amount, a.currency, a.status, a.assigned_to, a.closed_on,"
        + " a.closure_reason, a.closed_by, a.report_id, cur.scale, cur.rounding_mode"
        + "  FROM aml_alert a LEFT JOIN currency cur ON cur.code = a.currency";

    public static Alert require(Connection c, UUID alertId) {
        return find(c, alertId).orElseThrow(
            () -> new IllegalArgumentException("Alerte inconnue : " + alertId));
    }

    public static Optional<Alert> find(Connection c, UUID alertId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE a.id = ?")) {
            ps.setObject(1, alertId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(c, rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'alerte " + alertId, e);
        }
    }

    /** La file de la conformite : les alertes d'une entite, filtrees par statut. */
    public static List<Alert> alerts(Connection c, UUID legalEntityId, String status) {
        List<Alert> alerts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE a.legal_entity_id = ? AND (?::text IS NULL OR a.status = ?)"
            + " ORDER BY a.raised_on DESC, a.created_at DESC")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    alerts.add(read(c, rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Alertes de l'entite", e);
        }
        return alerts;
    }

    private static Alert read(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        String code = rs.getString(9);
        Money amount = code == null ? null
            : Money.of(rs.getBigDecimal(8), new CurrencyRef(code, rs.getInt(16),
                                                            RoundingMode.valueOf(rs.getString(17))))
                .roundToCurrency();
        return new Alert(id, rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                         rs.getString(4), Origin.valueOf(rs.getString(5)),
                         rs.getObject(6, LocalDate.class), rs.getString(7), amount,
                         rs.getString(10), rs.getObject(11, UUID.class),
                         rs.getObject(12, LocalDate.class), rs.getString(13),
                         rs.getObject(14, UUID.class), rs.getObject(15, UUID.class), items(c, id));
    }

    private static List<Item> items(Connection c, UUID alertId) {
        List<Item> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT i.entry_id, i.booking_date, i.account_id, i.direction, i.amount, i.currency,"
            + " cur.scale, cur.rounding_mode FROM aml_alert_item i"
            + "  JOIN currency cur ON cur.code = i.currency"
            + " WHERE i.alert_id = ? ORDER BY i.booking_date, i.entry_id")) {
            ps.setObject(1, alertId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(6), rs.getInt(7),
                        RoundingMode.valueOf(rs.getString(8)));
                    items.add(new Item(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                                       rs.getObject(3, UUID.class), rs.getString(4),
                                       Money.of(rs.getBigDecimal(5), currency).roundToCurrency()));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Pieces de l'alerte " + alertId, e);
        }
        return items;
    }

    static void update(Connection c, String sql, Object first, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, first);
            ps.setObject(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de l'alerte " + id, e);
        }
    }

    static BigDecimal scaled(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
