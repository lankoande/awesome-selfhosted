package io.corebanking.loan.service;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.Contagion;
import io.corebanking.loan.RiskBucket;
import io.corebanking.loan.RiskGrid;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Depot des profils de risque : creation, activation, resolution datee.
 *
 * <p><b>Une grille incoherente ne peut pas etre enregistree.</b> La validation
 * ({@link RiskGrid}) s'execute avant l'insertion. Refuser a l'enregistrement plutot qu'a
 * l'activation evite qu'une grille trouee ou a taux decroissants dorme en base, ou quelqu'un
 * finira par l'activer en urgence a la veille d'un arrete.
 */
public final class RiskProfiles {

    private RiskProfiles() {}

    public record Draft(UUID legalEntityId, String label, LocalDate validFrom, LocalDate validTo,
                        RiskGrid grid, UUID createdBy) {}

    public static UUID createDraft(Connection c, Draft draft) {
        RiskGrid grid = draft.grid();               // deja validee a la construction
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO risk_profile(id, legal_entity_id, code, label, valid_from, valid_to,"
            + " contagion, suspend_from_bucket, status, created_by)"
            + " VALUES (?,?,?,?,?,?,?,?,'DRAFT',?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, grid.code());
            ps.setString(4, draft.label());
            ps.setObject(5, draft.validFrom());
            ps.setObject(6, draft.validTo());
            ps.setString(7, grid.contagion().name());
            ps.setString(8, grid.suspendFromBucket());
            ps.setObject(9, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du profil de risque " + grid.code(), e);
        }
        insertBuckets(c, id, grid);
        return id;
    }

    public static void activate(Connection c, UUID profileId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE risk_profile SET status = 'ACTIVE', approved_by = ?"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, profileId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Profil " + profileId + " introuvable ou deja sorti de l'etat DRAFT.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation du profil " + profileId, e);
        }
    }

    /** Grille en vigueur a une date. Une periode non couverte est une erreur, jamais un repli. */
    public static RiskGrid resolveAt(Connection c, UUID legalEntityId, String code,
                                     LocalDate date) {
        UUID id;
        Contagion contagion;
        String suspendFrom;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, contagion, suspend_from_bucket FROM risk_profile"
            + " WHERE legal_entity_id = ? AND code = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setObject(3, date);
            ps.setObject(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new io.corebanking.product.ProductNotFoundException(
                        "profil de risque " + code, date);
                }
                id = rs.getObject(1, UUID.class);
                contagion = Contagion.valueOf(rs.getString(2));
                suspendFrom = rs.getString(3);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Resolution du profil de risque " + code, e);
        }

        List<RiskBucket> buckets = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT ordinal, code, label, from_days, to_days, provision_rate_percent, performing"
            + " FROM risk_bucket WHERE profile_id = ? ORDER BY ordinal")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer toDays = rs.getObject(5, Integer.class);
                    buckets.add(new RiskBucket(rs.getInt(1), rs.getString(2), rs.getString(3),
                                               rs.getInt(4), toDays, rs.getBigDecimal(6),
                                               rs.getBoolean(7)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des classes du profil " + code, e);
        }
        // Revalidee a la relecture : une grille alteree en base par un correctif manuel serait
        // sinon appliquee telle quelle a tout le portefeuille.
        return new RiskGrid(code, buckets, contagion, suspendFrom);
    }

    private static void insertBuckets(Connection c, UUID profileId, RiskGrid grid) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO risk_bucket(profile_id, ordinal, code, label, from_days, to_days,"
            + " provision_rate_percent, performing) VALUES (?,?,?,?,?,?,?,?)")) {
            for (RiskBucket bucket : grid.buckets()) {
                ps.setObject(1, profileId);
                ps.setInt(2, bucket.ordinal());
                ps.setString(3, bucket.code());
                ps.setString(4, bucket.label());
                ps.setInt(5, bucket.fromDays());
                ps.setObject(6, bucket.toDays());
                ps.setBigDecimal(7, bucket.provisionRatePercent());
                ps.setBoolean(8, bucket.performing());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des classes de risque", e);
        }
    }
}
