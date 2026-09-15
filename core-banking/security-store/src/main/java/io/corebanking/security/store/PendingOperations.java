package io.corebanking.security.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.security.Caller;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registre des operations en attente de double validation.
 *
 * <p>Ce registre ne sait rien de ce que les operations font : il garde la requete telle que
 * recue, qui l'a saisie, qui a decide, et ce que l'execution a rendu. C'est l'API qui sait
 * rejouer la requete — et elle ne le fait qu'apres la decision d'un checker habilite.
 */
public final class PendingOperations {

    private PendingOperations() {}

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED, FAILED }

    public record Draft(UUID legalEntityId, String operation, String handler, String resource,
                        String payload, BigDecimal amount, String currency, Caller maker,
                        Duration validity) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(maker, "maker");
            Objects.requireNonNull(validity, "validity");
        }
    }

    public record Pending(UUID id, UUID legalEntityId, String operation, String handler,
                          String resource, String payload, BigDecimal amount, String currency,
                          Status status, String makerId, String makerUsername, UUID makerBranchId,
                          Instant madeAt, Instant expiresAt, String decidedBy, Instant decidedAt,
                          String decisionReason, String result, String error, Instant executedAt) {

        public boolean expired(Instant now) {
            return status == Status.PENDING && !now.isBefore(expiresAt);
        }
    }

    public static UUID submit(Connection c, Draft draft) {
        UUID id = Ids.newId();
        Instant now = Instant.now();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO pending_operation(id, legal_entity_id, operation, handler, resource,"
            + " payload, amount, currency, maker_id, maker_username, maker_branch_id, made_at,"
            + " expires_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.operation());
            ps.setString(4, draft.handler());
            ps.setString(5, draft.resource());
            ps.setString(6, draft.payload());
            ps.setBigDecimal(7, draft.amount());
            ps.setString(8, draft.currency());
            ps.setString(9, draft.maker().subjectId());
            ps.setString(10, draft.maker().username());
            ps.setObject(11, draft.maker().branchId());
            ps.setTimestamp(12, Timestamp.from(now));
            ps.setTimestamp(13, Timestamp.from(now.plus(draft.validity())));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'operation en attente", e);
        }
        return id;
    }

    public static Pending require(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new UnknownPendingOperationException(id);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'operation en attente " + id, e);
        }
    }

    /** Operations en attente d'une entite, de la plus ancienne a la plus recente. */
    public static List<Pending> pending(Connection c, UUID legalEntityId) {
        List<Pending> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND status = 'PENDING' ORDER BY made_at")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des operations en attente", e);
        }
        return list;
    }

    /**
     * Enregistre la decision d'un checker. La base refuse un checker qui serait le maker ; une
     * seconde decision du meme checker est refusee aussi.
     */
    public static void decide(Connection c, UUID id, Caller checker, boolean approved,
                              String reason) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO operation_approval(id, operation_id, checker_id, checker_username,"
            + " decision, reason) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, id);
            ps.setString(3, checker.subjectId());
            ps.setString(4, checker.username());
            ps.setString(5, approved ? "APPROVED" : "REJECTED");
            ps.setString(6, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Decision sur l'operation en attente " + id
                                           + " : " + e.getMessage(), e);
        }
        update(c, id, approved ? Status.APPROVED : Status.REJECTED,
               "decided_by = ?, decided_at = now(), decision_reason = ?",
               checker.subjectId(), reason);
    }

    public static void executed(Connection c, UUID id, String resultJson) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE pending_operation SET status = 'EXECUTED', result = ?::jsonb,"
            + " executed_at = now() WHERE id = ?")) {
            ps.setString(1, resultJson);
            ps.setObject(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de l'operation en attente " + id, e);
        }
    }

    public static void failed(Connection c, UUID id, String error) {
        update(c, id, Status.FAILED, "error = ?", error, null);
    }

    public static void expire(Connection c, UUID id) {
        update(c, id, Status.EXPIRED, "error = ?", "expiree sans decision", null);
    }

    private static void update(Connection c, UUID id, Status status, String set, String p1,
                               String p2) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE pending_operation SET status = ?, " + set + " WHERE id = ?")) {
            ps.setString(1, status.name());
            int index = 2;
            ps.setString(index++, p1);
            if (set.contains("decision_reason")) {
                ps.setString(index++, p2);
            }
            ps.setObject(index, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de l'operation en attente " + id, e);
        }
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, operation, handler, resource, payload::text, amount,"
        + " currency, status, maker_id, maker_username, maker_branch_id, made_at, expires_at,"
        + " decided_by, decided_at, decision_reason, result::text, error, executed_at"
        + " FROM pending_operation";

    private static Pending read(ResultSet rs) throws SQLException {
        return new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                           rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                           rs.getBigDecimal(7), rs.getString(8) == null ? null
                                                                          : rs.getString(8).trim(),
                           Status.valueOf(rs.getString(9)), rs.getString(10), rs.getString(11),
                           rs.getObject(12, UUID.class), instant(rs.getTimestamp(13)),
                           instant(rs.getTimestamp(14)), rs.getString(15),
                           instant(rs.getTimestamp(16)), rs.getString(17), rs.getString(18),
                           rs.getString(19), instant(rs.getTimestamp(20)));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public static class UnknownPendingOperationException extends RuntimeException {
        public UnknownPendingOperationException(UUID id) {
            super("Operation en attente inconnue : " + id);
        }
    }
}
