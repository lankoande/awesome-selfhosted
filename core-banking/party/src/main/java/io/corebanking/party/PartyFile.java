package io.corebanking.party;

import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Completude du dossier de connaissance client.
 *
 * <p>Ce qui manque a un dossier ne bloque pas les comptes existants : il empeche d'en ouvrir de
 * nouveaux. C'est la restriction progressive que le dossier reglementaire attend — une piece qui
 * expire ne doit pas arreter le salaire qui tombe sur le compte, mais elle doit arreter le credit
 * qu'on allait accorder.
 *
 * <p>Sans politique de diligence declaree pour la nature et le niveau du tiers, rien n'est exige,
 * et la completude le dit ({@code policyDeclared}) plutot que de rendre un dossier complet qui
 * n'aurait ete confronte a rien.
 */
public final class PartyFile {

    private PartyFile() {}

    /**
     * @param missing           pieces exigees et absentes
     * @param expired           pieces exigees et arrivees a expiration
     * @param unverifiedOwners  beneficiaires effectifs au-dela du seuil dont le dossier n'est pas
     *                          verifie : les connaitre, c'est avoir leur dossier, pas leur nom
     */
    public record Completeness(UUID partyId, String reference, PartyKind kind, KycLevel level,
                               boolean policyDeclared, List<DocumentKind> missing,
                               List<DocumentKind> expired, boolean beneficialOwnersMissing,
                               List<String> unverifiedOwners) {

        public Completeness {
            missing = List.copyOf(missing);
            expired = List.copyOf(expired);
            unverifiedOwners = List.copyOf(unverifiedOwners);
        }

        public boolean complete() {
            return missing.isEmpty() && expired.isEmpty() && !beneficialOwnersMissing
                   && unverifiedOwners.isEmpty();
        }

        /** Ce qui manque, en une phrase — celle que l'agence lira au refus d'ouverture. */
        public String summary() {
            if (complete()) {
                return "dossier complet";
            }
            List<String> gaps = new ArrayList<>();
            if (!missing.isEmpty()) {
                gaps.add("pieces manquantes : " + missing);
            }
            if (!expired.isEmpty()) {
                gaps.add("pieces expirees : " + expired);
            }
            if (beneficialOwnersMissing) {
                gaps.add("aucun beneficiaire effectif declare");
            }
            if (!unverifiedOwners.isEmpty()) {
                gaps.add("beneficiaires effectifs non verifies : " + unverifiedOwners);
            }
            return String.join(" ; ", gaps);
        }
    }

    /** Confronte le dossier d'un tiers a la politique de diligence de sa nature et de son niveau. */
    public static Completeness completeness(Connection c, UUID partyId, LocalDate on) {
        Party party = Parties.require(c, partyId);
        Optional<KycPolicies.Policy> policy = KycPolicies.find(c, party.legalEntityId(),
                                                               party.kind(), party.kycLevel());
        if (policy.isEmpty()) {
            return new Completeness(partyId, party.reference(), party.kind(), party.kycLevel(),
                                    false, List.of(), List.of(), false, List.of());
        }
        List<PartyDocuments.Document> documents = PartyDocuments.of(c, partyId, false);
        List<DocumentKind> missing = new ArrayList<>();
        List<DocumentKind> expired = new ArrayList<>();
        for (DocumentKind required : policy.get().requiredDocuments()) {
            Optional<PartyDocuments.Document> held = documents.stream()
                .filter(d -> d.kind() == required).findFirst();
            if (held.isEmpty()) {
                missing.add(required);
            } else if (held.get().expiredOn(on)) {
                expired.add(required);
            }
        }
        missing.sort(java.util.Comparator.naturalOrder());
        expired.sort(java.util.Comparator.naturalOrder());

        boolean ownersMissing = false;
        List<String> unverified = new ArrayList<>();
        if (policy.get().beneficialOwnersRequired() && party.kind() == PartyKind.LEGAL_PERSON) {
            List<BeneficialOwners.Owner> owners = BeneficialOwners.current(c, partyId);
            ownersMissing = owners.isEmpty();
            BigDecimal threshold = policy.get().ownershipThresholdPercent();
            for (BeneficialOwners.Owner owner : owners) {
                if (owner.ownershipPercent().compareTo(threshold) < 0) {
                    continue;
                }
                Party person = Parties.require(c, owner.ownerPartyId());
                if (person.kycStatus() != KycStatus.VERIFIED) {
                    unverified.add(person.reference());
                }
            }
        }
        return new Completeness(partyId, party.reference(), party.kind(), party.kycLevel(), true,
                                missing, expired, ownersMissing, unverified);
    }

    /** Les dossiers incomplets de l'entite : la liste de travail de la conformite. */
    public static List<Completeness> incomplete(Connection c, UUID legalEntityId, LocalDate on) {
        List<Completeness> incomplete = new ArrayList<>();
        try (var ps = c.prepareStatement(
            "SELECT id FROM party WHERE legal_entity_id = ? AND status = 'ACTIVE' ORDER BY reference")) {
            ps.setObject(1, legalEntityId);
            try (var rs = ps.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
                for (UUID id : ids) {
                    Completeness completeness = completeness(c, id, on);
                    if (completeness.policyDeclared() && !completeness.complete()) {
                        incomplete.add(completeness);
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            throw new LedgerStoreException("Recensement des dossiers incomplets", e);
        }
        return incomplete;
    }
}
