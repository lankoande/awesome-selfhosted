package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * La balance : pour chaque compte, le solde d'ouverture, les mouvements et le solde de cloture
 * d'une plage de dates comptables, en six colonnes — la forme que lit un comptable et que
 * demande un commissaire aux comptes.
 *
 * <p>Elle est lue dans le journal, jamais dans un cliche : ce que la balance montre est
 * exactement ce que les ecritures disent, a la date demandee, et elle se recalcule a
 * l'identique tant que le journal ne change pas. Le ledger tient un seul type de compte, clients
 * et generaux dans le meme journal ; la balance de tous les comptes est donc la balance
 * generale, et la balance auxiliaire des comptes clients en est un filtre, pas une autre
 * lecture. Un filtre d'agence donne la balance d'agence, sur la dimension d'agence des lignes.
 *
 * <p>Les totaux par devise s'equilibrent par construction — ouverture, mouvements et cloture ;
 * ils sont rendus avec la balance, et un desequilibre serait une corruption du journal, pas
 * un fait de gestion.
 */
public final class TrialBalance {

    private TrialBalance() {}

    /** Une ligne de la balance : un compte, six colonnes ; un solde n'occupe qu'une colonne. */
    public record Line(UUID accountId, String code, AccountKind kind, AccountNature nature,
                       NormalBalance normalBalance, CurrencyRef currency,
                       Money openingDebit, Money openingCredit,
                       Money movementDebit, Money movementCredit,
                       Money closingDebit, Money closingCredit) {}

    /** Les totaux d'une devise : ce qui doit s'equilibrer, colonne a colonne. */
    public record Totals(CurrencyRef currency, long accounts,
                         Money openingDebit, Money openingCredit,
                         Money movementDebit, Money movementCredit,
                         Money closingDebit, Money closingCredit) {

        public boolean balanced() {
            return openingDebit.equals(openingCredit) && movementDebit.equals(movementCredit)
                   && closingDebit.equals(closingCredit);
        }
    }

    /** Filtre facultatif : nature de compte (balance auxiliaire) et agence (balance d'agence). */
    public record Filter(AccountKind kind, UUID branchId) {
        public static final Filter NONE = new Filter(null, null);
    }

    private static final String PER_ACCOUNT =
        "SELECT a.id, a.code, a.account_kind, a.nature, a.normal_balance, a.currency,"
        + " SUM(CASE WHEN l.booking_date < ? AND l.direction = 'DEBIT' THEN l.amount ELSE 0 END)"
        + " AS od,"
        + " SUM(CASE WHEN l.booking_date < ? AND l.direction = 'CREDIT' THEN l.amount ELSE 0 END)"
        + " AS oc,"
        + " SUM(CASE WHEN l.booking_date >= ? AND l.direction = 'DEBIT' THEN l.amount ELSE 0 END)"
        + " AS md,"
        + " SUM(CASE WHEN l.booking_date >= ? AND l.direction = 'CREDIT' THEN l.amount ELSE 0 END)"
        + " AS mc"
        + " FROM journal_line l JOIN account a ON a.id = l.account_id"
        + " WHERE l.legal_entity_id = ? AND l.booking_date <= ?";

    private static final String GROUP =
        " GROUP BY a.id, a.code, a.account_kind, a.nature, a.normal_balance, a.currency";

    /**
     * Une page de la balance, dans l'ordre des codes de compte. Un compte y figure des qu'une
     * ligne le mouvemente jusqu'a la fin de la plage, meme si tout y est nul.
     *
     * @param from premier jour de la plage : ce qui precede est le solde d'ouverture
     * @param to   dernier jour de la plage, inclus
     */
    public static List<Line> page(Connection c, UUID legalEntityId, LocalDate from, LocalDate to,
                                  Filter filter, int offset, int limit) {
        requireRange(from, to);
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT t.*, cur.scale, cur.rounding_mode FROM (" + PER_ACCOUNT + filterSql(filter)
            + GROUP + ") t JOIN currency cur ON cur.code = t.currency"
            + " ORDER BY t.code, t.id OFFSET ? LIMIT ?")) {
            int i = bind(ps, legalEntityId, from, to, filter);
            ps.setInt(i++, offset);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(6), rs.getInt(11),
                                                           RoundingMode.valueOf(rs.getString(12)));
                    BigDecimal od = rs.getBigDecimal(7);
                    BigDecimal oc = rs.getBigDecimal(8);
                    BigDecimal md = rs.getBigDecimal(9);
                    BigDecimal mc = rs.getBigDecimal(10);
                    BigDecimal opening = od.subtract(oc);
                    BigDecimal closing = opening.add(md).subtract(mc);
                    lines.add(new Line(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        AccountKind.valueOf(rs.getString(3)),
                        AccountNature.valueOf(rs.getString(4)),
                        NormalBalance.valueOf(rs.getString(5)), currency,
                        debitSide(opening, currency), creditSide(opening, currency),
                        Money.of(md, currency), Money.of(mc, currency),
                        debitSide(closing, currency), creditSide(closing, currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Balance de l'entite " + legalEntityId, e);
        }
        return lines;
    }

    /** Le nombre de comptes de la balance : ses bornes de pagination. */
    public static long count(Connection c, UUID legalEntityId, LocalDate from, LocalDate to,
                             Filter filter) {
        requireRange(from, to);
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM (" + PER_ACCOUNT + filterSql(filter) + GROUP + ") t")) {
            bind(ps, legalEntityId, from, to, filter);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte de la balance de l'entite " + legalEntityId, e);
        }
    }

    /** Les totaux, une ligne par devise, sur les memes comptes que la balance filtree. */
    public static List<Totals> totals(Connection c, UUID legalEntityId, LocalDate from,
                                      LocalDate to, Filter filter) {
        requireRange(from, to);
        List<Totals> totals = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode, count(*),"
            + " SUM(GREATEST(t.od - t.oc, 0)), SUM(GREATEST(t.oc - t.od, 0)),"
            + " SUM(t.md), SUM(t.mc),"
            + " SUM(GREATEST(t.od - t.oc + t.md - t.mc, 0)),"
            + " SUM(GREATEST(t.oc - t.od + t.mc - t.md, 0))"
            + " FROM (" + PER_ACCOUNT + filterSql(filter) + GROUP + ") t"
            + " JOIN currency cur ON cur.code = t.currency"
            + " GROUP BY cur.code, cur.scale, cur.rounding_mode ORDER BY cur.code")) {
            bind(ps, legalEntityId, from, to, filter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(1), rs.getInt(2),
                                                           RoundingMode.valueOf(rs.getString(3)));
                    totals.add(new Totals(currency, rs.getLong(4),
                        Money.of(rs.getBigDecimal(5), currency),
                        Money.of(rs.getBigDecimal(6), currency),
                        Money.of(rs.getBigDecimal(7), currency),
                        Money.of(rs.getBigDecimal(8), currency),
                        Money.of(rs.getBigDecimal(9), currency),
                        Money.of(rs.getBigDecimal(10), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Totaux de la balance de l'entite " + legalEntityId, e);
        }
        return totals;
    }

    // ------------------------------------------------------------------ interne

    private static void requireRange(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Plage de dates inversee : du " + from + " au " + to);
        }
    }

    private static String filterSql(Filter filter) {
        return (filter.branchId() == null ? "" : " AND l.branch_id = ?")
               + (filter.kind() == null ? "" : " AND a.account_kind = ?");
    }

    /** Lie les parametres de la sous-requete par compte ; rend l'indice du parametre suivant. */
    private static int bind(PreparedStatement ps, UUID legalEntityId, LocalDate from,
                            LocalDate to, Filter filter) throws SQLException {
        ps.setObject(1, from);
        ps.setObject(2, from);
        ps.setObject(3, from);
        ps.setObject(4, from);
        ps.setObject(5, legalEntityId);
        ps.setObject(6, to);
        int i = 7;
        if (filter.branchId() != null) {
            ps.setObject(i++, filter.branchId());
        }
        if (filter.kind() != null) {
            ps.setString(i++, filter.kind().name());
        }
        return i;
    }

    private static Money debitSide(BigDecimal signed, CurrencyRef currency) {
        return Money.of(signed.signum() > 0 ? signed : BigDecimal.ZERO, currency);
    }

    private static Money creditSide(BigDecimal signed, CurrencyRef currency) {
        return Money.of(signed.signum() < 0 ? signed.negate() : BigDecimal.ZERO, currency);
    }
}
