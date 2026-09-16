package io.corebanking.party;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
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
 * Beneficiaires effectifs d'une personne morale.
 *
 * <p>Un beneficiaire effectif est une <b>personne physique</b> du referentiel : le connaitre
 * signifie avoir son dossier, pas son nom. Une chaine de societes ecran se remonte donc jusqu'a
 * une personne, et la declaration porte la part detenue.
 *
 * <p>La somme des parts declarees ne depasse pas 100 % : au-dela, une des declarations est fausse,
 * et c'est le genre d'erreur qu'un etat de concentration recopie sans la voir.
 */
public final class BeneficialOwners {

    private BeneficialOwners() {}

    public record Owner(UUID id, UUID legalEntityId, UUID partyId, UUID ownerPartyId,
                        String ownerReference, String ownerName, BigDecimal ownershipPercent,
                        LocalDate declaredOn, LocalDate validTo, UUID createdBy, UUID approvedBy) {}

    public record Declaration(UUID legalEntityId, UUID partyId, UUID ownerPartyId,
                              BigDecimal ownershipPercent, LocalDate declaredOn, UUID createdBy,
                              UUID approvedBy) {
        public Declaration {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(ownerPartyId, "ownerPartyId");
            Objects.requireNonNull(declaredOn, "declaredOn");
            if (partyId.equals(ownerPartyId)) {
                throw new IllegalArgumentException("Une personne morale ne se detient pas elle-meme");
            }
            if (ownershipPercent == null || ownershipPercent.signum() <= 0
                || ownershipPercent.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(
                    "La part detenue est de 0 exclu a 100 : " + ownershipPercent);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Un beneficiaire effectif se declare a deux : "
                    + "le demandeur ne peut pas etre le valideur");
            }
        }
    }

    public static Owner declare(Connection c, Declaration declaration) {
        Party party = Parties.require(c, declaration.partyId());
        Party owner = Parties.require(c, declaration.ownerPartyId());
        if (!party.legalEntityId().equals(declaration.legalEntityId())
            || !owner.legalEntityId().equals(declaration.legalEntityId())) {
            throw new IllegalArgumentException(
                "Le tiers et son beneficiaire relevent de la meme entite juridique");
        }
        if (party.kind() != PartyKind.LEGAL_PERSON) {
            throw new IllegalArgumentException("Seule une personne morale a des beneficiaires "
                + "effectifs : " + party.reference() + " est une personne physique");
        }
        if (owner.kind() != PartyKind.NATURAL_PERSON) {
            throw new IllegalArgumentException("Un beneficiaire effectif est une personne physique :"
                + " remonter la chaine de detention jusqu'a une personne, pas jusqu'a une societe "
                + "ecran — " + owner.reference() + " est une personne morale");
        }
        BigDecimal declared = totalDeclared(c, declaration.partyId());
        BigDecimal total = declared.add(declaration.ownershipPercent());
        if (total.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Les parts declarees sur " + party.reference()
                + " totaliseraient " + total.stripTrailingZeros().toPlainString()
                + " % : une des declarations est fausse");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO beneficial_owner(id, legal_entity_id, party_id, owner_party_id,"
            + " ownership_percent, declared_on, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, declaration.legalEntityId());
            ps.setObject(3, declaration.partyId());
            ps.setObject(4, declaration.ownerPartyId());
            ps.setBigDecimal(5, declaration.ownershipPercent());
            ps.setObject(6, declaration.declaredOn());
            ps.setObject(7, declaration.createdBy());
            ps.setObject(8, declaration.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException(owner.reference() + " est deja declare beneficiaire"
                    + " effectif de " + party.reference() + " : mettre fin a la declaration en "
                    + "vigueur avant d'en poser une autre", e);
            }
            throw new LedgerStoreException("Declaration du beneficiaire effectif", e);
        }
        Parties.event(c, declaration.partyId(), "BENEFICIAL_OWNER_ADDED", declaration.declaredOn(),
                      declaration.createdBy(), declaration.approvedBy(),
                      owner.reference() + " pour "
                      + declaration.ownershipPercent().stripTrailingZeros().toPlainString() + " %",
                      null);
        return current(c, declaration.partyId()).stream().filter(o -> o.id().equals(id))
            .findFirst().orElseThrow();
    }

    /** Met fin a une declaration : la detention a change de main, l'historique reste. */
    public static void end(Connection c, UUID id, LocalDate on, UUID actorId, UUID approverId) {
        if (approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException("La fin d'une declaration se decide a deux");
        }
        UUID partyId;
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE beneficial_owner SET valid_to = ? WHERE id = ? AND valid_to IS NULL"
            + " RETURNING party_id")) {
            ps.setObject(1, on);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                        "Declaration inconnue ou deja terminee : " + id);
                }
                partyId = rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Fin de la declaration de beneficiaire", e);
        }
        Parties.event(c, partyId, "BENEFICIAL_OWNER_ENDED", on, actorId, approverId,
                      "declaration " + id, null);
    }

    /** Les beneficiaires en vigueur d'une personne morale, du plus detenteur au moins. */
    public static List<Owner> current(Connection c, UUID partyId) {
        List<Owner> owners = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT b.id, b.legal_entity_id, b.party_id, b.owner_party_id, o.reference,"
            + " o.display_name, b.ownership_percent, b.declared_on, b.valid_to, b.created_by,"
            + " b.approved_by FROM beneficial_owner b JOIN party o ON o.id = b.owner_party_id"
            + " WHERE b.party_id = ? AND b.valid_to IS NULL"
            + " ORDER BY b.ownership_percent DESC, o.reference")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(new Owner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getString(6), rs.getBigDecimal(7), rs.getObject(8, LocalDate.class),
                        rs.getObject(9, LocalDate.class), rs.getObject(10, UUID.class),
                        rs.getObject(11, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des beneficiaires effectifs", e);
        }
        return owners;
    }

    private static BigDecimal totalDeclared(Connection c, UUID partyId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Owner owner : current(c, partyId)) {
            total = total.add(owner.ownershipPercent());
        }
        return total;
    }
}
