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
}
