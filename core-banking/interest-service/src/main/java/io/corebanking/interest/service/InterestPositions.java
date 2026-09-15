package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Position d'interets d'un compte : l'etat courant du calcul, par cote.
 *
 * <h2>Pourquoi une position, et pas une somme</h2>
 *
 * <p>L'etat d'un calcul — derniere journee remuneree, cumul exact, total impute — se deduit des
 * journees calculees. Le deduire chaque nuit par une somme sur toutes les journees d'un compte
 * coute O(historique) par compte : deux ans a deux millions de comptes, c'est un milliard et demi
 * de lignes lues chaque nuit pour retrouver ce que l'on savait la veille. La position est cette
 * connaissance, tenue a jour a chaque calcul et a chaque reglement.
 *
 * <h2>Ce que la position garantit</h2>
 *
 * <p>Elle est une projection : elle se reconstruit depuis les journees et les reglements actifs,
 * et c'est ce que fait toute annulation. {@code posted_total - settled_total} est ce que le
 * sous-livre affirme se trouver au compte de courus ; la reconciliation le verifie chaque nuit.
 */
public final class InterestPositions {

    private InterestPositions() {}

    public record Key(UUID accountId, AccrualSide side) {}

    public record Position(UUID accountId, AccrualSide side, UUID accruedAccountId,
                           LocalDate accruedThrough, Money cumulativePrecise, Money postedTotal,
                           Money settledTotal, LocalDate settledThrough, int generation) {

        public static Position empty(UUID accountId, AccrualSide side, CurrencyRef currency) {
            return new Position(accountId, side, null, null, Money.zero(currency),
                                Money.zero(currency), Money.zero(currency), null, 0);
        }

        /** Courus imputes et non encore regles : ce que le compte de courus doit porter. */
        public Money unsettled() {
            return postedTotal.minus(settledTotal);
        }
    }

    // ------------------------------------------------------------------ lecture

    public static Position load(Connection c, UUID accountId, AccrualSide side,
                                CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT accrued_account_id, accrued_through, cumulative_precise, posted_total,"
            + " settled_total, settled_through, generation"
            + " FROM interest_position WHERE account_id = ? AND side = ?")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Position.empty(accountId, side, currency);
                }
                return read(rs, accountId, side, currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la position d'interets", e);
        }
    }

    /** Positions d'un lot de comptes, tous cotes confondus, en un acces. */
    public static Map<Key, Position> loadAll(Connection c, Collection<UUID> accountIds,
                                             Map<UUID, CurrencyRef> currencies) {
        Map<Key, Position> positions = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT account_id, side, accrued_account_id, accrued_through, cumulative_precise,"
            + " posted_total, settled_total, settled_through, generation"
            + " FROM interest_position WHERE account_id = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("uuid", accountIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID accountId = rs.getObject(1, UUID.class);
                    AccrualSide side = AccrualSide.valueOf(rs.getString(2));
                    CurrencyRef currency = currencies.get(accountId);
                    if (currency == null) {
                        continue;
                    }
                    positions.put(new Key(accountId, side), new Position(
                        accountId, side, rs.getObject(3, UUID.class),
                        rs.getObject(4, LocalDate.class),
                        Money.of(rs.getBigDecimal(5), currency),
                        Money.of(rs.getBigDecimal(6), currency),
                        Money.of(rs.getBigDecimal(7), currency),
                        rs.getObject(8, LocalDate.class), rs.getInt(9)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des positions d'interets du lot", e);
        }
        return positions;
    }

    private static Position read(ResultSet rs, UUID accountId, AccrualSide side,
                                 CurrencyRef currency) throws SQLException {
        return new Position(accountId, side, rs.getObject(1, UUID.class),
                            rs.getObject(2, LocalDate.class),
                            Money.of(rs.getBigDecimal(3), currency),
                            Money.of(rs.getBigDecimal(4), currency),
                            Money.of(rs.getBigDecimal(5), currency),
                            rs.getObject(6, LocalDate.class), rs.getInt(7));
    }

    /** Cumul exact arrondi a une journee de valeur, si cette journee a ete calculee. */
    public static Optional<Money> roundedCumulativeAt(Connection c, UUID accountId,
                                                      AccrualSide side, LocalDate day,
                                                      CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cumulative_precise FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND accrual_date = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                    ? Optional.of(Money.of(rs.getBigDecimal(1), currency).roundToCurrency())
                    : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Cumul des interets courus au " + day, e);
        }
    }

    /**
     * Positions qui peuvent avoir une fin de periode a regler : calculees, et pas encore reglees
     * jusqu'a la derniere fin de mois. Toute periodicite de reglement est un multiple du mois :
     * une position reglee jusqu'a la derniere fin de mois n'a rien a regler, quelle que soit sa
     * periodicite. Ce filtre ne lit qu'une table, indexee ; le reglement lui-meme tranche.
     */
    public static List<Key> settlementCandidates(Connection c, UUID legalEntityId,
                                                 LocalDate lastMonthEnd) {
        List<Key> keys = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT p.account_id, p.side FROM interest_position p"
            + " JOIN account a ON a.id = p.account_id"
            + " WHERE a.legal_entity_id = ? AND p.accrued_through IS NOT NULL"
            + "   AND (p.settled_through IS NULL OR p.settled_through < ?)"
            + " ORDER BY p.account_id, p.side")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, lastMonthEnd);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(new Key(rs.getObject(1, UUID.class),
                                     AccrualSide.valueOf(rs.getString(2))));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des positions a regler", e);
        }
        return keys;
    }

    // ------------------------------------------------------------------ mise a jour

    /** Enregistre l'etat du calcul apres une imputation. Les reglements sont conserves. */
    public static void recordAccrual(Connection c, UUID accountId, AccrualSide side,
                                     UUID accruedAccountId, LocalDate through, Money cumulative,
                                     Money postedTotal, int generation) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO interest_position(account_id, side, accrued_account_id, accrued_through,"
            + " cumulative_precise, posted_total, generation)"
            + " VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (account_id, side) DO UPDATE SET"
            + "   accrued_account_id = EXCLUDED.accrued_account_id,"
            + "   accrued_through = EXCLUDED.accrued_through,"
            + "   cumulative_precise = EXCLUDED.cumulative_precise,"
            + "   posted_total = EXCLUDED.posted_total,"
            + "   generation = GREATEST(interest_position.generation, EXCLUDED.generation),"
            + "   updated_at = now()")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, accruedAccountId);
            ps.setObject(4, through);
            ps.setBigDecimal(5, cumulative.amount());
            ps.setBigDecimal(6, postedTotal.amount());
            ps.setInt(7, generation);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de la position d'interets", e);
        }
    }

    /** Enregistre un reglement : ce qui vient d'etre repris du compte de courus. */
    public static void recordSettlement(Connection c, UUID accountId, AccrualSide side,
                                        Money gross, LocalDate periodEnd) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE interest_position SET settled_total = settled_total + ?,"
            + " settled_through = ?, updated_at = now() WHERE account_id = ? AND side = ?")) {
            ps.setBigDecimal(1, gross.amount());
            ps.setObject(2, periodEnd);
            ps.setObject(3, accountId);
            ps.setString(4, side.name());
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Reglement sans position d'interets pour le compte " + accountId
                    + " : rien n'a jamais ete calcule sur ce cote.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du reglement d'interets", e);
        }
    }

    // ------------------------------------------------------------------ reconstruction

    /**
     * Reconstruit la position depuis les journees et les reglements actifs.
     *
     * <p>C'est l'operation de verite : tout ce que la position affirme doit se retrouver ici. Elle
     * ne s'execute qu'apres une neutralisation — annulation d'un traitement, recalcul retroactif —
     * et lit alors l'historique du compte, ce qui est acceptable pour un evenement rare.
     */
    public static void rebuild(Connection c, UUID accountId, AccrualSide side) {
        LocalDate through = null;
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal posted = BigDecimal.ZERO;
        int generation = 0;
        BigDecimal settled = BigDecimal.ZERO;
        LocalDate settledThrough = null;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT accrual_date, cumulative_precise, posted_cumulative FROM interest_accrual"
            + " WHERE account_id = ? AND side = ? AND status = 'ACTIVE'"
            + " ORDER BY accrual_date DESC LIMIT 1")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    through = rs.getObject(1, LocalDate.class);
                    cumulative = rs.getBigDecimal(2);
                    posted = rs.getBigDecimal(3);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconstruction de la position : journees", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(MAX(generation), 0) FROM interest_accrual"
            + " WHERE account_id = ? AND side = ?")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                generation = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconstruction de la position : generation", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(gross_amount), 0), MAX(period_end) FROM interest_settlement"
            + " WHERE account_id = ? AND side = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                settled = rs.getBigDecimal(1);
                settledThrough = rs.getObject(2, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconstruction de la position : reglements", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO interest_position(account_id, side, accrued_through, cumulative_precise,"
            + " posted_total, settled_total, settled_through, generation)"
            + " VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (account_id, side) DO UPDATE SET"
            + "   accrued_through = EXCLUDED.accrued_through,"
            + "   cumulative_precise = EXCLUDED.cumulative_precise,"
            + "   posted_total = EXCLUDED.posted_total,"
            + "   settled_total = EXCLUDED.settled_total,"
            + "   settled_through = EXCLUDED.settled_through,"
            + "   generation = EXCLUDED.generation,"
            + "   updated_at = now()")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, through);
            ps.setBigDecimal(4, cumulative);
            ps.setBigDecimal(5, posted);
            ps.setBigDecimal(6, settled);
            ps.setObject(7, settledThrough);
            ps.setInt(8, generation);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconstruction de la position d'interets", e);
        }
    }

    /**
     * Neutralise les journees calculees a partir d'une date de valeur, sans les supprimer, et
     * reconstruit la position. Les ecritures correspondantes sont contre-passees par l'appelant.
     */
    public static void reverseFrom(Connection c, UUID accountId, AccrualSide side, LocalDate from) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE interest_accrual SET status = 'REVERSED'"
            + " WHERE account_id = ? AND side = ? AND accrual_date >= ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, from);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des journees recalculees", e);
        }
        rebuild(c, accountId, side);
    }

    /**
     * Neutralise tout ce qu'un traitement a calcule et regle — journees et reglements — puis
     * reconstruit les positions touchees. Sans cette neutralisation, contre-passer les ecritures
     * laisserait le moteur croire ces journees remunerees, et elles ne le seraient plus jamais.
     *
     * @return nombre de positions reconstruites
     */
    public static int cancelRun(Connection c, UUID runId) {
        List<Key> touched = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT account_id, side FROM interest_accrual WHERE batch_run_id = ?"
            + " UNION SELECT DISTINCT account_id, side FROM interest_settlement"
            + " WHERE batch_run_id = ?")) {
            ps.setObject(1, runId);
            ps.setObject(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    touched.add(new Key(rs.getObject(1, UUID.class),
                                        AccrualSide.valueOf(rs.getString(2))));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des positions touchees par le traitement", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE interest_accrual SET status = 'REVERSED' WHERE batch_run_id = ?"
            + " AND status = 'ACTIVE'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Neutralisation des interets calcules par le traitement annule", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE interest_settlement SET status = 'REVERSED' WHERE batch_run_id = ?"
            + " AND status = 'ACTIVE'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Neutralisation des reglements d'interets du traitement annule", e);
        }
        for (Key key : touched) {
            rebuild(c, key.accountId(), key.side());
        }
        return touched.size();
    }

    /** Devise d'un compte, pour lire sa position. */
    public static CurrencyRef currencyOf(Connection c, UUID accountId) {
        return Balances.currencyOf(c, accountId);
    }
}
