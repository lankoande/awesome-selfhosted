package io.corebanking.party;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Politique de diligence : ce que la banque exige d'un dossier.
 *
 * <p>Par nature de tiers et niveau de diligence : les pieces a produire, l'obligation de
 * connaitre les beneficiaires effectifs, et la part de detention a partir de laquelle un
 * beneficiaire doit l'etre. Le tout se declare a deux, et se lit.
 *
 * <h2>Sans politique, rien n'est exige</h2>
 *
 * <p>Une banque qui n'a rien declare n'a rien exige : le systeme ne presume pas une liste de
 * pieces qu'il faudrait deviner. C'est la meme regle que pour les plafonds, les suspens et les
 * conditions de banque — le parametrage dit ce qu'il attend, et son absence se voit dans la
 * lecture de la politique, pas dans un refus inexplique au guichet.
 */
public final class KycPolicies {

    private KycPolicies() {}

    public record Policy(UUID id, UUID legalEntityId, PartyKind partyKind, KycLevel kycLevel,
                         Set<DocumentKind> requiredDocuments, boolean beneficialOwnersRequired,
                         BigDecimal ownershipThresholdPercent, UUID createdBy, UUID approvedBy) {}

    public record Draft(UUID legalEntityId, PartyKind partyKind, KycLevel kycLevel,
                        Set<DocumentKind> requiredDocuments, boolean beneficialOwnersRequired,
                        BigDecimal ownershipThresholdPercent, UUID createdBy, UUID approvedBy) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(partyKind, "partyKind");
            Objects.requireNonNull(kycLevel, "kycLevel");
            requiredDocuments = requiredDocuments == null ? Set.of() : Set.copyOf(requiredDocuments);
            if (beneficialOwnersRequired && partyKind != PartyKind.LEGAL_PERSON) {
                throw new IllegalArgumentException("Les beneficiaires effectifs sont une exigence "
                    + "des personnes morales : une personne physique n'en a pas");
            }
            if (ownershipThresholdPercent == null) {
                ownershipThresholdPercent = new BigDecimal("25");
            }
            if (ownershipThresholdPercent.signum() <= 0
                || ownershipThresholdPercent.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(
                    "Le seuil de detention est une part de 0 exclu a 100 : "
                    + ownershipThresholdPercent);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une politique de diligence se declare a deux : "
                    + "le demandeur ne peut pas etre le valideur");
            }
        }
    }

    public static Policy declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO kyc_policy(id, legal_entity_id, party_kind, kyc_level,"
            + " beneficial_owners_required, ownership_threshold_percent, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.partyKind().name());
            ps.setString(4, draft.kycLevel().name());
            ps.setBoolean(5, draft.beneficialOwnersRequired());
            ps.setBigDecimal(6, draft.ownershipThresholdPercent());
            ps.setObject(7, draft.createdBy());
            ps.setObject(8, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Une politique de diligence existe deja pour "
                    + draft.partyKind() + " en niveau " + draft.kycLevel()
                    + " : elle se remplace, elle ne se double pas", e);
            }
            throw new LedgerStoreException("Declaration de la politique de diligence", e);
        }
        if (!draft.requiredDocuments().isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kyc_policy_document(policy_id, document_kind) VALUES (?,?)")) {
                for (DocumentKind kind : draft.requiredDocuments()) {
                    ps.setObject(1, id);
                    ps.setString(2, kind.name());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new LedgerStoreException("Pieces exigees par la politique", e);
            }
        }
        return find(c, draft.legalEntityId(), draft.partyKind(), draft.kycLevel()).orElseThrow();
    }

    /** Remplace la politique d'une nature et d'un niveau : l'ancienne disparait avec ses pieces. */
    public static Policy replace(Connection c, Draft draft) {
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM kyc_policy WHERE legal_entity_id = ? AND party_kind = ? AND kyc_level = ?")) {
            ps.setObject(1, draft.legalEntityId());
            ps.setString(2, draft.partyKind().name());
            ps.setString(3, draft.kycLevel().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Remplacement de la politique de diligence", e);
        }
        return declare(c, draft);
    }

    public static Optional<Policy> find(Connection c, UUID legalEntityId, PartyKind partyKind,
                                        KycLevel kycLevel) {
        return all(c, legalEntityId).stream()
            .filter(p -> p.partyKind() == partyKind && p.kycLevel() == kycLevel)
            .findFirst();
    }

    public static List<Policy> all(Connection c, UUID legalEntityId) {
        List<Policy> policies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT p.id, p.legal_entity_id, p.party_kind, p.kyc_level,"
            + " p.beneficial_owners_required, p.ownership_threshold_percent, p.created_by,"
            + " p.approved_by, d.document_kind"
            + " FROM kyc_policy p LEFT JOIN kyc_policy_document d ON d.policy_id = p.id"
            + " WHERE p.legal_entity_id = ? ORDER BY p.party_kind, p.kyc_level")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                UUID current = null;
                Set<DocumentKind> documents = EnumSet.noneOf(DocumentKind.class);
                Policy pending = null;
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    if (!id.equals(current)) {
                        if (pending != null) {
                            policies.add(withDocuments(pending, documents));
                        }
                        current = id;
                        documents = EnumSet.noneOf(DocumentKind.class);
                        pending = new Policy(id, rs.getObject(2, UUID.class),
                            PartyKind.valueOf(rs.getString(3)), KycLevel.valueOf(rs.getString(4)),
                            Set.of(), rs.getBoolean(5), rs.getBigDecimal(6),
                            rs.getObject(7, UUID.class), rs.getObject(8, UUID.class));
                    }
                    String document = rs.getString(9);
                    if (document != null) {
                        documents.add(DocumentKind.valueOf(document));
                    }
                }
                if (pending != null) {
                    policies.add(withDocuments(pending, documents));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des politiques de diligence", e);
        }
        return policies;
    }

    private static Policy withDocuments(Policy policy, Set<DocumentKind> documents) {
        return new Policy(policy.id(), policy.legalEntityId(), policy.partyKind(),
                          policy.kycLevel(), Set.copyOf(documents),
                          policy.beneficialOwnersRequired(), policy.ownershipThresholdPercent(),
                          policy.createdBy(), policy.approvedBy());
    }
}
