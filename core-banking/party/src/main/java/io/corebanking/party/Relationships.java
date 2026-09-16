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
 * Relations entre tiers : mandataire, representant legal, conjoint, groupe.
 *
 * <p>Elles portent la vue groupe et la contagion de declassement : un impaye chez une filiale
 * n'est pas un fait isole. Une relation de detention — {@code PARENT_COMPANY} — se lit en chaine,
 * et le systeme refuse d'en fermer une : une societe qui serait sa propre mere, meme au sixieme
 * rang, ferait boucler tout parcours de groupe, et le premier a s'en apercevoir serait le
 * traitement de nuit.
 */
public final class Relationships {

    private Relationships() {}

    public record Relationship(UUID id, UUID legalEntityId, UUID fromPartyId, String fromReference,
                               UUID toPartyId, String toReference, String toName,
                               RelationshipKind kind, LocalDate validFrom, LocalDate validTo,
                               UUID createdBy, UUID approvedBy) {}

    public record Draft(UUID legalEntityId, UUID fromPartyId, UUID toPartyId, RelationshipKind kind,
                        LocalDate validFrom, UUID createdBy, UUID approvedBy) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(fromPartyId, "fromPartyId");
            Objects.requireNonNull(toPartyId, "toPartyId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(validFrom, "validFrom");
            if (fromPartyId.equals(toPartyId)) {
                throw new IllegalArgumentException("Un tiers n'est pas en relation avec lui-meme");
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une relation se declare a deux : elle donne un "
                    + "pouvoir ou engage un groupe ; le demandeur ne peut pas etre le valideur");
            }
        }
    }

    public static Relationship declare(Connection c, Draft draft) {
        Party from = Parties.require(c, draft.fromPartyId());
        Party to = Parties.require(c, draft.toPartyId());
        if (!from.legalEntityId().equals(draft.legalEntityId())
            || !to.legalEntityId().equals(draft.legalEntityId())) {
            throw new IllegalArgumentException(
                "Les deux tiers d'une relation relevent de la meme entite juridique");
        }
        if (draft.kind() == RelationshipKind.PARENT_COMPANY) {
            if (from.kind() != PartyKind.LEGAL_PERSON || to.kind() != PartyKind.LEGAL_PERSON) {
                throw new IllegalArgumentException(
                    "Une societe mere et sa filiale sont deux personnes morales");
            }
            // Un cycle peut naitre de deux declarations dont aucune, seule, n'en ferme un :
            // A vers B et C vers A, posees en meme temps, ne se voient pas l'une l'autre. Aucune
            // ligne ne les porte toutes deux — il n'y a donc rien a verrouiller par ligne : les
            // declarations de detention d'une entite se serialisent entre elles, et elles seules.
            // Elles sont rares, et validees a deux.
            lockHoldings(c, draft.legalEntityId());
            if (reaches(c, draft.toPartyId(), draft.fromPartyId())) {
                throw new IllegalArgumentException(to.reference() + " descend deja de "
                    + from.reference() + " : la detention formerait un cycle, et tout parcours de "
                    + "groupe tournerait sans fin");
            }
        }
        if (draft.kind() == RelationshipKind.LEGAL_REPRESENTATIVE
            && to.kind() != PartyKind.NATURAL_PERSON) {
            throw new IllegalArgumentException(
                "Un representant legal est une personne physique : " + to.reference());
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party_relationship(id, legal_entity_id, from_party_id, to_party_id, kind,"
            + " valid_from, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setObject(3, draft.fromPartyId());
            ps.setObject(4, draft.toPartyId());
            ps.setString(5, draft.kind().name());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.createdBy());
            ps.setObject(8, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("La relation " + draft.kind() + " de "
                    + from.reference() + " vers " + to.reference() + " est deja en vigueur", e);
            }
            throw new LedgerStoreException("Declaration de la relation", e);
        }
        Parties.event(c, draft.fromPartyId(), "RELATIONSHIP_ADDED", draft.validFrom(),
                      draft.createdBy(), draft.approvedBy(),
                      draft.kind() + " vers " + to.reference(), null);
        return require(c, id);
    }

    /** Serialise les declarations de detention d'une entite, le temps de la transaction. */
    private static void lockHoldings(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext('party_relationship'), hashtext(?))")) {
            ps.setString(1, legalEntityId.toString());
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou des declarations de detention", e);
        }
    }

    /** Met fin a une relation : le pouvoir cesse, la trace reste. */
    public static void end(Connection c, UUID id, LocalDate on, UUID actorId, UUID approverId) {
        if (approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException("La fin d'une relation se decide a deux");
        }
        Relationship relationship = require(c, id);
        if (relationship.validTo() != null) {
            throw new IllegalStateException("Relation deja terminee le " + relationship.validTo());
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE party_relationship SET valid_to = ? WHERE id = ? AND valid_to IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Fin de la relation", e);
        }
        Parties.event(c, relationship.fromPartyId(), "RELATIONSHIP_ENDED", on, actorId, approverId,
                      relationship.kind() + " vers " + relationship.toReference(), null);
    }

    private static final String SELECT =
        "SELECT r.id, r.legal_entity_id, r.from_party_id, f.reference, r.to_party_id, t.reference,"
        + " t.display_name, r.kind, r.valid_from, r.valid_to, r.created_by, r.approved_by"
        + " FROM party_relationship r JOIN party f ON f.id = r.from_party_id"
        + " JOIN party t ON t.id = r.to_party_id";

    public static Relationship require(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE r.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Relation inconnue : " + id);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la relation", e);
        }
    }

    /** Les relations en vigueur d'un tiers, celles qu'il porte et celles dont il est la cible. */
    public static List<Relationship> of(Connection c, UUID partyId) {
        List<Relationship> relationships = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE (r.from_party_id = ? OR r.to_party_id = ?) AND r.valid_to IS NULL"
            + " ORDER BY r.kind, t.reference")) {
            ps.setObject(1, partyId);
            ps.setObject(2, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relationships.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des relations du tiers", e);
        }
        return relationships;
    }

    /**
     * Le groupe d'un tiers : lui-meme et tout ce que la chaine de detention relie, dans les deux
     * sens. C'est l'assiette d'une contagion de declassement.
     */
    public static List<UUID> group(Connection c, UUID partyId) {
        List<UUID> members = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "WITH RECURSIVE g(id) AS ("
            + "    SELECT ?::uuid"
            + "  UNION"
            + "    SELECT CASE WHEN r.from_party_id = g.id THEN r.to_party_id ELSE r.from_party_id END"
            + "      FROM party_relationship r JOIN g"
            + "        ON (r.from_party_id = g.id OR r.to_party_id = g.id)"
            + "     WHERE r.valid_to IS NULL AND r.kind IN ('PARENT_COMPANY','GROUP_MEMBER'))"
            + " SELECT id FROM g ORDER BY id")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du groupe du tiers", e);
        }
        return members;
    }

    /** Vrai si {@code ancestor} est atteignable depuis {@code start} en remontant les detentions. */
    private static boolean reaches(Connection c, UUID start, UUID ancestor) {
        try (PreparedStatement ps = c.prepareStatement(
            "WITH RECURSIVE up(id) AS ("
            + "    SELECT ?::uuid"
            + "  UNION"
            + "    SELECT r.to_party_id FROM party_relationship r JOIN up ON r.from_party_id = up.id"
            + "     WHERE r.valid_to IS NULL AND r.kind = 'PARENT_COMPANY')"
            + " SELECT 1 FROM up WHERE id = ? LIMIT 1")) {
            ps.setObject(1, start);
            ps.setObject(2, ancestor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Parcours de la chaine de detention", e);
        }
    }

    private static Relationship read(ResultSet rs) throws SQLException {
        return new Relationship(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, UUID.class),
            rs.getString(6), rs.getString(7), RelationshipKind.valueOf(rs.getString(8)),
            rs.getObject(9, LocalDate.class), rs.getObject(10, LocalDate.class),
            rs.getObject(11, UUID.class), rs.getObject(12, UUID.class));
    }
}
