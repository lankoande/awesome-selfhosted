package io.corebanking.compliance;

import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/** Lectures de contexte : l'entite d'un tiers, et la date comptable de cette entite. */
final class ComplianceDates {

    private ComplianceDates() {}

    static UUID entityOf(Connection c, UUID partyId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT legal_entity_id FROM party WHERE id = ?")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Tiers inconnu : " + partyId);
                }
                return rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Entite du tiers " + partyId, e);
        }
    }

    static LocalDate businessDate(Connection c, UUID partyId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT e.current_business_date FROM legal_entity e"
            + "  JOIN party p ON p.legal_entity_id = e.id WHERE p.id = ?")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Tiers inconnu : " + partyId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Date comptable du tiers " + partyId, e);
        }
    }

    static LocalDate businessDateOfEntity(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Date comptable de l'entite", e);
        }
    }
}
