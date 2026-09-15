package io.corebanking.party;

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

/** Acces au referentiel client. Les regles sont dans {@link PartyService} ; ici, la persistance. */
public final class Parties {

    private Parties() {}

    private static final String SELECT =
        "SELECT id, legal_entity_id, reference, kind, display_name, birth_or_registration_date,"
        + " country_code, segment, kyc_level, kyc_status, kyc_verified_on, kyc_review_due,"
        + " risk_rating, status, status_reason FROM party";

    public static Optional<Party> find(Connection c, UUID partyId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du tiers " + partyId, e);
        }
    }

    public static Party require(Connection c, UUID partyId) {
        return find(c, partyId).orElseThrow(
            () -> new IllegalArgumentException("Tiers inconnu : " + partyId));
    }

    public static Optional<Party> findByReference(Connection c, UUID legalEntityId, String reference) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND reference = ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, reference);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche du tiers " + reference, e);
        }
    }

    /** Tiers de l'entite portant cet identifiant officiel, s'il existe. */
    public static Optional<Party> findByIdentifier(Connection c, UUID legalEntityId,
                                                   IdentifierKind kind, String value) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE id = (SELECT party_id FROM party_identifier"
            + " WHERE legal_entity_id = ? AND kind = ? AND value = ? LIMIT 1)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, kind.name());
            ps.setString(3, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche par identifiant " + kind, e);
        }
    }

    public static List<PartyIdentifier> identifiersOf(Connection c, UUID partyId) {
        List<PartyIdentifier> identifiers = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind, value, issued_on, expires_on, issuer FROM party_identifier"
            + " WHERE party_id = ? ORDER BY kind, value")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    identifiers.add(new PartyIdentifier(IdentifierKind.valueOf(rs.getString(1)),
                                                        rs.getString(2),
                                                        rs.getObject(3, LocalDate.class),
                                                        rs.getObject(4, LocalDate.class),
                                                        rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Identifiants du tiers " + partyId, e);
        }
        return identifiers;
    }

    // ------------------------------------------------------------------ ecriture

    static void insert(Connection c, UUID id, PartyService.Draft draft) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party(id, legal_entity_id, reference, kind, display_name,"
            + " birth_or_registration_date, country_code, segment, created_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.reference());
            ps.setString(4, draft.kind().name());
            ps.setString(5, draft.displayName());
            ps.setObject(6, draft.birthOrRegistrationDate());
            ps.setString(7, draft.countryCode());
            ps.setString(8, draft.segment());
            ps.setObject(9, draft.actorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du tiers " + draft.reference() + " refusee : "
                                           + e.getMessage(), e);
        }
    }

    static void insertIdentifier(Connection c, UUID partyId, UUID legalEntityId,
                                 PartyIdentifier identifier) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party_identifier(party_id, legal_entity_id, kind, value, issued_on,"
            + " expires_on, issuer) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, partyId);
            ps.setObject(2, legalEntityId);
            ps.setString(3, identifier.kind().name());
            ps.setString(4, identifier.value());
            ps.setObject(5, identifier.issuedOn());
            ps.setObject(6, identifier.expiresOn());
            ps.setString(7, identifier.issuer());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PartyService.DuplicatePartyException(
                "un tiers de l'entite porte deja l'identifiant " + identifier.kind() + " "
                + identifier.value(), e);
        }
    }

    static void updateKyc(Connection c, UUID partyId, KycStatus status, KycLevel level,
                          RiskRating rating, LocalDate verifiedOn, LocalDate reviewDue,
                          UUID verifiedBy) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party SET kyc_status = ?, kyc_level = ?, risk_rating = ?, kyc_verified_on = ?,"
            + " kyc_review_due = ?, kyc_verified_by = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, level.name());
            ps.setString(3, rating == null ? null : rating.name());
            ps.setObject(4, verifiedOn);
            ps.setObject(5, reviewDue);
            ps.setObject(6, verifiedBy);
            ps.setObject(7, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de la connaissance du tiers " + partyId, e);
        }
    }

    static void updateStatus(Connection c, UUID partyId, PartyStatus status, String reason) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party SET status = ?, status_reason = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setString(2, reason);
            ps.setObject(3, partyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour du statut du tiers " + partyId, e);
        }
    }

    static void event(Connection c, UUID partyId, String kind, LocalDate on, UUID actorId,
                      UUID approverId, String detail, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party_event(party_id, kind, occurred_on, actor_id, approver_id, detail,"
            + " batch_run_id) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, partyId);
            ps.setString(2, kind);
            ps.setObject(3, on);
            ps.setObject(4, actorId);
            ps.setObject(5, approverId);
            ps.setString(6, detail);
            ps.setObject(7, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Evenement " + kind + " du tiers " + partyId, e);
        }
    }

    private static Party read(ResultSet rs) throws SQLException {
        String rating = rs.getString(13);
        return new Party(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            PartyKind.valueOf(rs.getString(4)), rs.getString(5), rs.getObject(6, LocalDate.class),
            rs.getString(7), rs.getString(8), KycLevel.valueOf(rs.getString(9)),
            KycStatus.valueOf(rs.getString(10)), rs.getObject(11, LocalDate.class),
            rs.getObject(12, LocalDate.class), rating == null ? null : RiskRating.valueOf(rating),
            PartyStatus.valueOf(rs.getString(14)), rs.getString(15));
    }

    // ------------------------------------------------------------------ recherche par pages

    private static final String SEARCH_WHERE =
        " WHERE legal_entity_id = ? AND (?::text IS NULL OR reference ILIKE ? OR display_name ILIKE ?)";

    /**
     * Les tiers d'une entite dont la reference ou le nom contient le texte cherche, par pages,
     * dans l'ordre du nom puis de la reference — un ordre total. Sans texte, tous les tiers.
     */
    public static List<Party> search(Connection c, UUID legalEntityId, String query, int offset,
                                     int limit) {
        List<Party> parties = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + SEARCH_WHERE + " ORDER BY display_name, reference OFFSET ? LIMIT ?")) {
            bindSearch(ps, legalEntityId, query);
            ps.setInt(5, offset);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parties.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche de tiers", e);
        }
        return parties;
    }

    public static long countSearch(Connection c, UUID legalEntityId, String query) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM party" + SEARCH_WHERE)) {
            bindSearch(ps, legalEntityId, query);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte des tiers", e);
        }
    }

    private static void bindSearch(PreparedStatement ps, UUID legalEntityId, String query)
            throws SQLException {
        String text = query == null || query.isBlank() ? null : query.trim();
        String pattern = text == null ? null
            : "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        ps.setObject(1, legalEntityId);
        ps.setString(2, text);
        ps.setString(3, pattern);
        ps.setString(4, pattern);
    }
}
