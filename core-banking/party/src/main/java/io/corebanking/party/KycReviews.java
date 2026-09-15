package io.corebanking.party;

import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Revue periodique de la connaissance client, pilotee par l'arrete.
 *
 * <p>Un dossier dont la revue est depassee passe en {@code EXPIRED} : ses comptes continuent de
 * fonctionner, mais rien de nouveau ne s'ouvre dessus tant qu'il n'est pas reverifie. C'est
 * l'etape {@code KYC_REVIEW} qui le constate, et l'annulation de l'arrete le defait.
 */
public final class KycReviews {

    private KycReviews() {}

    /** Passe en revue depassee les dossiers verifies dont l'echeance est anterieure a la journee. */
    public static List<String> expire(Connection c, UUID legalEntityId, LocalDate businessDate,
                                      UUID batchRunId, UUID actorId) {
        List<String> expired = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party SET kyc_status = 'EXPIRED', updated_at = now()"
            + " WHERE legal_entity_id = ? AND kyc_status = 'VERIFIED' AND kyc_review_due < ?"
            + " RETURNING id, reference, kyc_review_due")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Parties.event(c, rs.getObject(1, UUID.class), "KYC_EXPIRED", businessDate,
                                  actorId, null, "revue due au " + rs.getObject(3, LocalDate.class),
                                  batchRunId);
                    expired.add(rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Revue de la connaissance client", e);
        }
        return expired;
    }

    /** Defait les expirations prononcees par un traitement annule. */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party p SET kyc_status = 'VERIFIED', updated_at = now()"
            + " FROM party_event e WHERE e.party_id = p.id AND e.batch_run_id = ?"
            + "   AND e.kind = 'KYC_EXPIRED' AND p.kyc_status = 'EXPIRED'")) {
            ps.setObject(1, batchRunId);
            int restored = ps.executeUpdate();
            try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM party_event WHERE batch_run_id = ? AND kind = 'KYC_EXPIRED'")) {
                del.setObject(1, batchRunId);
                del.executeUpdate();
            }
            return restored;
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des revues du traitement", e);
        }
    }
}
