package io.corebanking.party;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
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
 * Pieces du dossier client.
 *
 * <p>Une piece est datee et peut expirer. Deposer une piece d'une nature deja presente
 * <b>remplace</b> la precedente sans l'effacer : le dossier garde ce qu'il a connu, et l'audit
 * doit pouvoir dire sur quelle piece une ouverture a ete decidee. Seule la derniere piece non
 * remplacee de chaque nature compte pour la completude.
 */
public final class PartyDocuments {

    private PartyDocuments() {}

    public record Document(UUID id, UUID legalEntityId, UUID partyId, DocumentKind kind,
                           String reference, String issuer, LocalDate issuedOn,
                           LocalDate expiresOn, LocalDate collectedOn, UUID supersededBy,
                           UUID createdBy) {
        /** Vrai si la piece est arrivee a expiration a la date donnee. */
        public boolean expiredOn(LocalDate on) {
            return expiresOn != null && expiresOn.isBefore(on);
        }
    }

    public record Deposit(UUID legalEntityId, UUID partyId, DocumentKind kind, String reference,
                          String issuer, LocalDate issuedOn, LocalDate expiresOn,
                          LocalDate collectedOn, UUID actorId) {
        public Deposit {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(collectedOn, "collectedOn");
            Objects.requireNonNull(actorId, "actorId");
            if (expiresOn != null && issuedOn != null && expiresOn.isBefore(issuedOn)) {
                throw new IllegalArgumentException("Une piece n'expire pas avant d'etre emise");
            }
        }
    }

    /** Depose une piece et remplace celle de la meme nature, si elle existe. */
    public static Document deposit(Connection c, Deposit deposit) {
        Party party = Parties.require(c, deposit.partyId());
        if (!party.legalEntityId().equals(deposit.legalEntityId())) {
            throw new IllegalArgumentException("Le tiers " + party.reference()
                                               + " releve d'une autre entite juridique");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party_document(id, legal_entity_id, party_id, kind, reference, issuer,"
            + " issued_on, expires_on, collected_on, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, deposit.legalEntityId());
            ps.setObject(3, deposit.partyId());
            ps.setString(4, deposit.kind().name());
            ps.setString(5, deposit.reference());
            ps.setString(6, deposit.issuer());
            ps.setObject(7, deposit.issuedOn());
            ps.setObject(8, deposit.expiresOn());
            ps.setObject(9, deposit.collectedOn());
            ps.setObject(10, deposit.actorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Depot de la piece " + deposit.kind(), e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party_document SET superseded_by = ?"
            + " WHERE party_id = ? AND kind = ? AND id <> ? AND superseded_by IS NULL")) {
            ps.setObject(1, id);
            ps.setObject(2, deposit.partyId());
            ps.setString(3, deposit.kind().name());
            ps.setObject(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Remplacement de la piece " + deposit.kind(), e);
        }
        Parties.event(c, deposit.partyId(), "DOCUMENT_ADDED", deposit.collectedOn(),
                      deposit.actorId(), null, deposit.kind().name()
                      + (deposit.expiresOn() == null ? "" : ", expire le " + deposit.expiresOn()),
                      null);
        return require(c, id);
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, party_id, kind, reference, issuer, issued_on, expires_on,"
        + " collected_on, superseded_by, created_by FROM party_document";

    public static Document require(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Piece inconnue : " + id);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la piece", e);
        }
    }

    /** Les pieces d'un dossier, la plus recente d'abord ; remplacees comprises si demande. */
    public static List<Document> of(Connection c, UUID partyId, boolean includeSuperseded) {
        List<Document> documents = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE party_id = ? AND (? OR superseded_by IS NULL)"
            + " ORDER BY collected_on DESC, created_at DESC")) {
            ps.setObject(1, partyId);
            ps.setBoolean(2, includeSuperseded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    documents.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des pieces du dossier", e);
        }
        return documents;
    }

    /**
     * Constate les pieces arrivees a expiration : un evenement par dossier concerne, une seule
     * fois par piece. L'annulation de l'arrete les efface.
     */
    public static List<String> expire(Connection c, UUID legalEntityId, LocalDate businessDate,
                                      UUID batchRunId, UUID actorId) {
        List<String> expired = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT d.id, d.party_id, d.kind, d.expires_on, p.reference"
            + "  FROM party_document d JOIN party p ON p.id = d.party_id"
            + " WHERE d.legal_entity_id = ? AND d.superseded_by IS NULL"
            + "   AND d.expires_on IS NOT NULL AND d.expires_on < ?"
            + "   AND NOT EXISTS (SELECT 1 FROM party_event e WHERE e.party_id = d.party_id"
            + "                     AND e.kind = 'DOCUMENT_EXPIRED' AND e.detail LIKE d.id || '%')"
            + " ORDER BY p.reference, d.kind")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    Parties.event(c, rs.getObject(2, UUID.class), "DOCUMENT_EXPIRED", businessDate,
                                  actorId, null,
                                  id + " " + rs.getString(3) + " expiree le "
                                  + rs.getObject(4, LocalDate.class), batchRunId);
                    expired.add(rs.getString(5) + " : " + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Constat des pieces expirees", e);
        }
        return expired;
    }

    /** Efface les constats d'expiration d'un traitement annule. */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM party_event WHERE batch_run_id = ? AND kind = 'DOCUMENT_EXPIRED'")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des constats d'expiration", e);
        }
    }

    private static Document read(ResultSet rs) throws SQLException {
        return new Document(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), DocumentKind.valueOf(rs.getString(4)),
                            rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                            rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class),
                            rs.getObject(10, UUID.class), rs.getObject(11, UUID.class));
    }
}
