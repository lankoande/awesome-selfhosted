package io.corebanking.interest.service;

import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconstitution de la serie des soldes <b>en date de valeur</b>, seule assiette licite de calcul
 * des interets.
 *
 * <p>La serie est reconstruite depuis le journal a chaque calcul, jamais lue dans un cache. C'est
 * ce qui rend le recalcul retroactif possible : une operation antidatee modifie la serie des
 * journees passees, et le moteur doit voir cette nouvelle serie, pas celle qu'il avait memorisee.
 *
 * <p>La serie produite est <b>contigue</b> : les journees sans mouvement reprennent le solde de la
 * veille. Un trou ferait disparaitre une journee d'interets sans qu'aucun controle d'equilibre ne
 * le signale.
 */
public final class ValueDatedSeries {

    private ValueDatedSeries() {}

    public static List<DailyBalance> build(Connection c, UUID accountId, LocalDate from,
                                           LocalDate throughInclusive) {
        if (throughInclusive.isBefore(from)) {
            return List.of();
        }
        CurrencyRef currency = currencyOf(c, accountId);
        BigDecimal running = openingBalance(c, accountId, from);
        Map<LocalDate, BigDecimal> deltas = dailyDeltas(c, accountId, from, throughInclusive);

        List<DailyBalance> series = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(throughInclusive); day = day.plusDays(1)) {
            running = running.add(deltas.getOrDefault(day, BigDecimal.ZERO));
            series.add(new DailyBalance(day, Money.of(running.setScale(5, RoundingMode.UNNECESSARY),
                                                      currency)));
        }
        return series;
    }

    /** Solde en date de valeur la veille du premier jour calcule. */
    private static BigDecimal openingBalance(Connection c, UUID accountId, LocalDate from) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount"
            + "                       ELSE -l.amount END), 0)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.account_id = ? AND l.value_date < ?")) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Solde d'ouverture en date de valeur", e);
        }
    }

    private static Map<LocalDate, BigDecimal> dailyDeltas(Connection c, UUID accountId,
                                                          LocalDate from, LocalDate through) {
        Map<LocalDate, BigDecimal> deltas = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.value_date,"
            + "       SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.account_id = ? AND l.value_date BETWEEN ? AND ?"
            + " GROUP BY l.value_date ORDER BY l.value_date")) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, through);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    deltas.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Mouvements en date de valeur", e);
        }
        return deltas;
    }

    /**
     * Premiere date de valeur mouvementee sur le compte : point de depart naturel du calcul
     * d'interets.
     */
    public static LocalDate firstValueDate(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MIN(value_date) FROM journal_line WHERE account_id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Premiere date de valeur du compte", e);
        }
    }

    private static CurrencyRef currencyOf(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode FROM account a"
            + " JOIN currency cur ON cur.code = a.currency WHERE a.id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Compte inconnu : " + accountId);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Devise du compte " + accountId, e);
        }
    }
}
