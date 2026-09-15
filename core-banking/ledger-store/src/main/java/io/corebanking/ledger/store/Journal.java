package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Relecture du journal. Lecture seule, par construction. */
public final class Journal {

    private Journal() {}

    /**
     * Relit les lignes d'une ecriture sous la forme de lignes de commande, pour contre-passation.
     * Les dates de valeur sont reprises telles quelles : c'est ce qui garantit que l'extourne
     * neutralise exactement les interets deja calcules.
     */
    public static List<PostingLine> loadPostingLines(Connection c, UUID entryId, LocalDate bookingDate) {
        List<PostingLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id, l.direction, l.amount, cur.code, cur.scale, cur.rounding_mode,"
            + " l.value_date, l.label, l.fx_rate, l.branch_id"
            + " FROM journal_line l JOIN currency cur ON cur.code = l.currency"
            + " WHERE l.entry_id = ? AND l.booking_date = ? ORDER BY l.line_number")) {
            ps.setObject(1, entryId);
            ps.setObject(2, bookingDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(4), rs.getInt(5),
                                                           RoundingMode.valueOf(rs.getString(6)));
                    lines.add(new PostingLine(
                        rs.getObject(1, UUID.class),
                        Direction.valueOf(rs.getString(2)),
                        Money.of(rs.getBigDecimal(3), currency),
                        rs.getObject(7, LocalDate.class),
                        rs.getString(8),
                        rs.getBigDecimal(9),
                        rs.getObject(10, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Relecture des lignes de l'ecriture " + entryId, e);
        }
        if (lines.isEmpty()) {
            throw new LedgerStoreException("Ecriture sans ligne : " + entryId);
        }
        return lines;
    }

    public static int countEntriesWithIdempotencyKey(Connection c, String key) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM journal_entry WHERE idempotency_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Comptage des ecritures par cle d'idempotence", e);
        }
    }

    // ------------------------------------------------------------------ releve et grand livre

    /**
     * Une ligne du journal, avec ce que son ecriture dit d'elle. Elle sert au releve d'un compte
     * comme a l'extraction du journal entier : la ligne dit toujours de quel compte elle est.
     */
    public record StatementLine(UUID entryId, long entryNumber, int lineNumber,
                                LocalDate bookingDate, Instant knowledgeTime, LocalDate valueDate,
                                UUID accountId, String accountCode, Direction direction,
                                Money amount, String label, String transactionType,
                                String narrative, UUID reversalOf, UUID branchId) {

        /** La position de cette ligne dans l'ordre du journal : la borne de la page suivante. */
        public Position position() {
            return new Position(bookingDate, knowledgeTime, entryId, lineNumber);
        }
    }

    /**
     * Une position dans l'ordre total du journal : date comptable, instant de connaissance,
     * ecriture, ligne. Ces quatre colonnes sont celles de la ligne elle-meme, dans l'ordre d'un
     * index : une lecture par curseur reprend strictement apres la position sans relire ce qui
     * precede, quoi qu'il ait ete comptabilise entre-temps. Dans une journee, l'ordre est celui
     * ou les ecritures ont ete connues — l'ordre chronologique du journal, celui de l'edition.
     */
    public record Position(LocalDate bookingDate, Instant knowledgeTime, UUID entryId,
                           int lineNumber) {
        public Position {
            Objects.requireNonNull(bookingDate, "bookingDate");
            Objects.requireNonNull(knowledgeTime, "knowledgeTime");
            Objects.requireNonNull(entryId, "entryId");
        }
    }

    private static final String SELECT_LINES =
        "SELECT l.entry_id, e.entry_number, l.line_number, l.booking_date, l.knowledge_time,"
        + " l.value_date, l.account_id, a.code, l.direction, l.amount, cur.code, cur.scale,"
        + " cur.rounding_mode, l.label, e.transaction_type, e.narrative, e.reversal_of,"
        + " l.branch_id"
        + " FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id"
        + " AND e.booking_date = l.booking_date"
        + " JOIN account a ON a.id = l.account_id"
        + " JOIN currency cur ON cur.code = l.currency";

    private static final String ORDER =
        " ORDER BY l.booking_date, l.knowledge_time, l.entry_id, l.line_number";

    private static final String STATEMENT_WHERE =
        " WHERE l.account_id = ? AND l.booking_date >= ? AND l.booking_date <= ?";

    private static final String JOURNAL_WHERE =
        " WHERE l.legal_entity_id = ? AND l.booking_date >= ? AND l.booking_date <= ?";

    private static final String AFTER =
        " AND (l.booking_date, l.knowledge_time, l.entry_id, l.line_number) > (?, ?, ?, ?)";

    /**
     * Les mouvements d'un compte sur une plage de dates comptables, dans l'ordre du journal, par
     * pages numerotees. L'ordre est total : deux lectures de la meme page rendent les memes
     * lignes, quoi qu'il ait ete comptabilise entre-temps sur les pages suivantes.
     */
    public static List<StatementLine> statement(Connection c, UUID accountId, LocalDate from,
                                                LocalDate to, int offset, int limit) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_LINES + STATEMENT_WHERE + ORDER + " OFFSET ? LIMIT ?")) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setInt(4, offset);
            ps.setInt(5, limit);
            return readLines(ps);
        } catch (SQLException e) {
            throw new LedgerStoreException("Releve du compte " + accountId, e);
        }
    }

    public static long countStatement(Connection c, UUID accountId, LocalDate from, LocalDate to) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM journal_line l" + STATEMENT_WHERE)) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte du releve du compte " + accountId, e);
        }
    }

    /**
     * Le grand livre d'un compte : ses mouvements apres une position, dans l'ordre du journal,
     * au plus {@code limit} lignes. {@code after} nul pour commencer au debut de la plage.
     */
    public static List<StatementLine> statementAfter(Connection c, UUID accountId, LocalDate from,
                                                     LocalDate to, Position after, int limit) {
        return readAfter(c, STATEMENT_WHERE, accountId, from, to, after, limit,
                         "Grand livre du compte " + accountId);
    }

    /**
     * Le journal de l'entite — toutes ses lignes, tous comptes confondus — apres une position.
     * C'est la lecture des extractions massives : le cout d'une page ne depend pas de ce qui la
     * precede, l'index porte les quatre colonnes de l'ordre.
     */
    public static List<StatementLine> journalAfter(Connection c, UUID legalEntityId, LocalDate from,
                                                   LocalDate to, Position after, int limit) {
        return readAfter(c, JOURNAL_WHERE, legalEntityId, from, to, after, limit,
                         "Journal de l'entite " + legalEntityId);
    }

    private static List<StatementLine> readAfter(Connection c, String where, UUID key,
                                                 LocalDate from, LocalDate to, Position after,
                                                 int limit, String what) {
        String sql = SELECT_LINES + where + (after == null ? "" : AFTER) + ORDER + " LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, key);
            ps.setObject(i++, from);
            ps.setObject(i++, to);
            if (after != null) {
                ps.setObject(i++, after.bookingDate());
                ps.setObject(i++, OffsetDateTime.ofInstant(after.knowledgeTime(), ZoneOffset.UTC));
                ps.setObject(i++, after.entryId());
                ps.setInt(i++, after.lineNumber());
            }
            ps.setInt(i, limit);
            return readLines(ps);
        } catch (SQLException e) {
            throw new LedgerStoreException(what, e);
        }
    }

    private static List<StatementLine> readLines(PreparedStatement ps) throws SQLException {
        List<StatementLine> lines = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                CurrencyRef currency = new CurrencyRef(rs.getString(11), rs.getInt(12),
                                                       RoundingMode.valueOf(rs.getString(13)));
                lines.add(new StatementLine(
                    rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3),
                    rs.getObject(4, LocalDate.class),
                    rs.getObject(5, OffsetDateTime.class).toInstant(),
                    rs.getObject(6, LocalDate.class), rs.getObject(7, UUID.class),
                    rs.getString(8), Direction.valueOf(rs.getString(9)),
                    Money.of(rs.getBigDecimal(10), currency), rs.getString(14),
                    rs.getString(15), rs.getString(16), rs.getObject(17, UUID.class),
                    rs.getObject(18, UUID.class)));
            }
        }
        return lines;
    }
}
