package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.balance.BalanceView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lecture des soldes.
 *
 * <p>Le solde materialise n'est qu'un cache : la source de verite reste le journal. Toute methode
 * de rejeu de cette classe reconstruit le solde a partir des lignes, sans jamais lire le cache.
 * L'ecart entre les deux est un invariant controle quotidiennement ({@link Reconciliation}).
 *
 * <p>Le ledger est <b>bitemporel</b> : deux axes independants permettent d'interroger un solde.
 *
 * <ul>
 *   <li>la <b>date comptable</b> — « quel etait le solde au 31 decembre » ;</li>
 *   <li>l'<b>instant de connaissance</b> — « ... tel qu'on le connaissait au 15 janvier ».</li>
 * </ul>
 *
 * <p>La seconde dimension est ce qui distingue ce socle des progiciels etablis. Quand un etat
 * regenere ne redonne pas le meme chiffre que l'original, ceux-ci ne savent que constater l'ecart.
 * Ici, {@link #asKnownAt} le reconstitue exactement, et la difference avec
 * {@link #replayAsOfBookingDate} isole precisement les ecritures arrivees entre-temps.
 */
public final class Balances {

    private static final String SIGNED_SUM =
        "SELECT COALESCE(SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount"
        + "                       ELSE -l.amount END), 0)"
        + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
        + " WHERE l.account_id = ?";

    private Balances() {}

    /** Solde materialise, somme des stripes. C'est la valeur servie au controle du disponible. */
    /**
     * Disponible a une date : solde comptable, moins les blocages de montant en vigueur, plus
     * l'autorisation de decouvert. C'est sur lui — jamais sur le solde — qu'un prelevement decide
     * de ce qu'il peut prendre : un blocage n'est pas de l'argent disponible.
     */
    public static Money available(Connection c, UUID accountId, LocalDate asOf) {
        CurrencyRef currency = currencyOf(c, accountId);
        try (PreparedStatement ps = c.prepareStatement("SELECT available_balance(?, ?)")) {
            ps.setObject(1, accountId);
            ps.setObject(2, asOf);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                BigDecimal value = rs.getBigDecimal(1);
                return Money.of(value == null ? BigDecimal.ZERO : value, currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Calcul du disponible du compte " + accountId, e);
        }
    }

    public static Money current(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(b.balance), 0), cur.code, cur.scale, cur.rounding_mode"
            + "  FROM account a JOIN currency cur ON cur.code = a.currency"
            + "  LEFT JOIN account_balance b ON b.account_id = a.id"
            + " WHERE a.id = ? GROUP BY cur.code, cur.scale, cur.rounding_mode")) {
            ps.setObject(1, accountId);
            return single(ps, accountId);
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du solde courant", e);
        }
    }

    /** Solde rejoue depuis le journal, en date comptable. */
    public static Money replayAsOfBookingDate(Connection c, UUID accountId, LocalDate asOf) {
        return replay(c, accountId, SIGNED_SUM + " AND l.booking_date <= ?", asOf, null);
    }

    /**
     * Solde rejoue en <b>date de valeur</b>. C'est la seule base licite de calcul des interets :
     * une operation antidatee modifie retroactivement cette serie, et donc tous les interets
     * courus depuis.
     */
    public static Money replayAsOfValueDate(Connection c, UUID accountId, LocalDate asOf) {
        return replay(c, accountId, SIGNED_SUM + " AND l.value_date <= ?", asOf, null);
    }

    /**
     * Solde a une date comptable, <b>tel qu'il etait connu</b> a un instant donne.
     *
     * <p>Les ecritures enregistrees apres cet instant sont exclues, quelle que soit leur date
     * comptable. C'est la requete qui explique pourquoi un etat produit en janvier et le meme etat
     * regenere en mars different.
     */
    public static BalanceView asKnownAt(Connection c, UUID accountId, LocalDate asOfBookingDate,
                                        Instant asKnownAt) {
        Money balance = replay(c, accountId,
            SIGNED_SUM + " AND l.booking_date <= ? AND l.knowledge_time <= ?",
            asOfBookingDate, asKnownAt);
        return new BalanceView(accountId, balance, asOfBookingDate, asKnownAt);
    }

    private static Money replay(Connection c, UUID accountId, String sql, LocalDate date,
                                Instant knowledgeLimit) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            ps.setObject(2, date);
            if (knowledgeLimit != null) {
                ps.setTimestamp(3, Timestamp.from(knowledgeLimit));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                BigDecimal amount = rs.getBigDecimal(1);
                return Money.of(amount == null ? BigDecimal.ZERO : amount, currencyOf(c, accountId));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rejeu du solde du compte " + accountId, e);
        }
    }

    private static Money single(PreparedStatement ps, UUID accountId) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new LedgerStoreException("Compte inconnu : " + accountId);
            }
            return Money.of(rs.getBigDecimal(1),
                            new CurrencyRef(rs.getString(2), rs.getInt(3),
                                            RoundingMode.valueOf(rs.getString(4))));
        }
    }

    /** Devise de tenue d'un compte. Publique : les moteurs metier en ont besoin. */
    public static CurrencyRef currencyOf(Connection c, UUID accountId) {
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
            throw new LedgerStoreException("Lecture de la devise du compte " + accountId, e);
        }
    }
}
