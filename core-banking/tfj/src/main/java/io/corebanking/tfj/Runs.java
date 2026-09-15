package io.corebanking.tfj;

import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lectures sur l'historique des traitements, partagees par les etapes. */
public final class Runs {

    private Runs() {}

    /**
     * Journee du dernier traitement de fin de journee <b>termine</b> avant une date : c'est celle
     * du dernier cliche de soldes complet, dont le cliche du jour repart.
     */
    public static Optional<LocalDate> previousCompletedDay(Connection c, UUID legalEntityId,
                                                           LocalDate before) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MAX(business_date) FROM batch_run"
            + " WHERE legal_entity_id = ? AND business_date < ? AND run_type = 'TFJ'"
            + "   AND mode = 'REAL' AND status = 'COMPLETED'")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, before);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche de la journee precedente", e);
        }
    }

    /** Journees d'une plage arretees par un traitement de fin de journee termine. */
    public static List<LocalDate> completedDays(Connection c, UUID legalEntityId, LocalDate from,
                                                LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT business_date FROM batch_run"
            + " WHERE legal_entity_id = ? AND business_date BETWEEN ? AND ?"
            + "   AND run_type = 'TFJ' AND mode = 'REAL' AND status = 'COMPLETED'"
            + " ORDER BY business_date")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    days.add(rs.getObject(1, LocalDate.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des journees arretees", e);
        }
        return days;
    }

    /** Premiere journee jamais arretee par l'entite, s'il y en a une. */
    public static Optional<LocalDate> firstDay(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MIN(business_date) FROM batch_run"
            + " WHERE legal_entity_id = ? AND run_type = 'TFJ' AND mode = 'REAL'"
            + "   AND status = 'COMPLETED'")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche de la premiere journee arretee", e);
        }
    }

    public static LocalDate currentBusinessDate(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite juridique inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la date comptable courante", e);
        }
    }
}
