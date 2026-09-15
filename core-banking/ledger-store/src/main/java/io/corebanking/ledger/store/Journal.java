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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    // ------------------------------------------------------------------ releve de compte

    /** Une ligne de releve : la ligne du journal, avec ce que son ecriture dit d'elle. */
    public record StatementLine(UUID entryId, long entryNumber, LocalDate bookingDate,
                                LocalDate valueDate, Direction direction, Money amount,
                                String label, String transactionType, String narrative,
                                UUID reversalOf, UUID branchId) {}

    private static final String STATEMENT_WHERE =
        " FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id"
        + " AND e.booking_date = l.booking_date"
        + " JOIN currency cur ON cur.code = l.currency"
        + " WHERE l.account_id = ? AND l.booking_date >= ? AND l.booking_date <= ?";

    /**
     * Les mouvements d'un compte sur une plage de dates comptables, dans l'ordre du journal —
     * date, numero d'ecriture, ligne — par pages. L'ordre est total : deux lectures de la meme
     * page rendent les memes lignes, quoi qu'il ait ete comptabilise entre-temps sur les pages
     * suivantes.
     */
    public static List<StatementLine> statement(Connection c, UUID accountId, LocalDate from,
                                                LocalDate to, int offset, int limit) {
        List<StatementLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.entry_id, e.entry_number, l.booking_date, l.value_date, l.direction,"
            + " l.amount, cur.code, cur.scale, cur.rounding_mode, l.label, e.transaction_type,"
            + " e.narrative, e.reversal_of, l.branch_id"
            + STATEMENT_WHERE
            + " ORDER BY l.booking_date, e.entry_number, l.line_number OFFSET ? LIMIT ?")) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setInt(4, offset);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(7), rs.getInt(8),
                                                           RoundingMode.valueOf(rs.getString(9)));
                    lines.add(new StatementLine(
                        rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        Direction.valueOf(rs.getString(5)),
                        Money.of(rs.getBigDecimal(6), currency), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getObject(13, UUID.class),
                        rs.getObject(14, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Releve du compte " + accountId, e);
        }
        return lines;
    }

    public static long countStatement(Connection c, UUID accountId, LocalDate from, LocalDate to) {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*)" + STATEMENT_WHERE)) {
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
}
