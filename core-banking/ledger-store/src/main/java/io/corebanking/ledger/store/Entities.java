package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/** Referentiel : devises, entites juridiques, periodes comptables. */
public final class Entities {

    private Entities() {}

    public static void insertCurrency(Connection c, CurrencyRef currency, String name) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO currency(code, scale, name, rounding_mode) VALUES (?,?,?,?) "
            + "ON CONFLICT (code) DO NOTHING")) {
            ps.setString(1, currency.code());
            ps.setInt(2, currency.scale());
            ps.setString(3, name);
            ps.setString(4, currency.roundingMode().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion de la devise " + currency, e);
        }
    }

    public static void insertLegalEntity(Connection c, UUID id, String code, String name,
                                         String countryCode, CurrencyRef functionalCurrency,
                                         LocalDate businessDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO legal_entity(id, code, name, country_code, functional_currency, "
            + "current_business_date) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, countryCode);
            ps.setString(5, functionalCurrency.code());
            ps.setObject(6, businessDate);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion de l'entite " + code, e);
        }
    }

    public static void openPeriod(Connection c, UUID entityId, LocalDate start, LocalDate end) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO accounting_period(id, legal_entity_id, start_date, end_date, status) "
            + "VALUES (?,?,?,?,'OPEN') ON CONFLICT (legal_entity_id, start_date) DO NOTHING")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, entityId);
            ps.setObject(3, start);
            ps.setObject(4, end);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Ouverture de periode", e);
        }
    }

    public static void closePeriod(Connection c, UUID entityId, LocalDate start) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_period SET status = 'CLOSED' "
            + "WHERE legal_entity_id = ? AND start_date = ?")) {
            ps.setObject(1, entityId);
            ps.setObject(2, start);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de periode", e);
        }
    }

    public static CurrencyRef functionalCurrency(Connection c, UUID entityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode "
            + "FROM legal_entity e JOIN currency cur ON cur.code = e.functional_currency "
            + "WHERE e.id = ?")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite juridique inconnue : " + entityId);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la devise de tenue de compte", e);
        }
    }

    /** Vrai si la date comptable tombe dans une periode ouverte de l'entite. */
    public static boolean isPeriodOpen(Connection c, UUID entityId, LocalDate bookingDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT status FROM accounting_period "
            + "WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date")) {
            ps.setObject(1, entityId);
            ps.setObject(2, bookingDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && ("OPEN".equals(rs.getString(1)) || "REOPENED".equals(rs.getString(1)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle de periode comptable", e);
        }
    }
}
