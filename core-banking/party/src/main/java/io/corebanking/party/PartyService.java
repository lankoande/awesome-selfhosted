package io.corebanking.party;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.Database;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Referentiel client : creation, connaissance client, blocage.
 *
 * <h2>Ce que le service garantit</h2>
 *
 * <ul>
 *   <li><b>Un tiers par personne.</b> Un identifiant officiel deja connu de l'entite refuse la
 *       creation en nommant le dossier existant. Un client en double casse les plafonds
 *       d'engagement et les etats de concentration ; le refus a la saisie coute une recherche,
 *       le doublon coute un contentieux.</li>
 *   <li><b>Rien ne s'ouvre sur un dossier non verifie.</b> Compte et credit exigent une
 *       connaissance client verifiee et non expiree. Les comptes existants d'un dossier expire
 *       continuent de fonctionner : la restriction est progressive, jamais un blocage brutal.</li>
 *   <li><b>Le filtrage precede l'existence.</b> Un tiers est soumis aux listes a sa creation ; une
 *       correspondance le cree bloque, en attente de levee de doute, plutot que de ne pas le
 *       creer — la tentative elle-meme est une information.</li>
 *   <li><b>La verification se fait a deux.</b> Celui qui verifie n'est pas celui qui valide.</li>
 * </ul>
 */
public final class PartyService {

    private final Database database;
    private final Screening screening;

    public PartyService(Database database, Screening screening) {
        this.database = Objects.requireNonNull(database, "database");
        this.screening = Objects.requireNonNull(screening, "screening");
    }

    /** Dossier a creer. Au moins un identifiant officiel : sans lui, aucun dedoublonnage. */
    public record Draft(UUID legalEntityId, String reference, PartyKind kind, String displayName,
                        LocalDate birthOrRegistrationDate, String countryCode, String segment,
                        List<PartyIdentifier> identifiers, UUID actorId) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(actorId, "actorId");
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("Reference client obligatoire");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("Nom obligatoire");
            }
            if (countryCode == null || countryCode.length() != 2) {
                throw new IllegalArgumentException("Code pays sur deux lettres obligatoire");
            }
            identifiers = List.copyOf(identifiers == null ? List.of() : identifiers);
            if (identifiers.stream().noneMatch(id -> id.kind().official())) {
                throw new IllegalArgumentException(
                    "Un tiers se cree avec au moins un identifiant officiel — piece d'identite, "
                    + "identifiant fiscal, registre du commerce : sans lui, rien ne distingue "
                    + "deux clients du meme nom.");
            }
        }
    }

    /** Cree le tiers, ou nomme le doublon. Renvoie l'identifiant du dossier cree. */
    public UUID create(Draft draft) {
        return database.inTransaction(c -> {
            for (PartyIdentifier identifier : draft.identifiers()) {
                if (!identifier.kind().official()) {
                    continue;
                }
                Optional<Party> existing = Parties.findByIdentifier(
                    c, draft.legalEntityId(), identifier.kind(), identifier.value());
                if (existing.isPresent()) {
                    throw new DuplicatePartyException(
                        "le dossier " + existing.get().reference() + " porte deja l'identifiant "
                        + identifier.kind() + " " + identifier.value() + ". Un client en double "
                        + "casse les plafonds d'engagement : rattacher, ne pas recreer.", null);
                }
            }
            UUID id = Ids.newId();
            Parties.insert(c, id, draft);
            for (PartyIdentifier identifier : draft.identifiers()) {
                Parties.insertIdentifier(c, id, draft.legalEntityId(), identifier);
            }
            LocalDate today = businessDate(c, draft.legalEntityId());
            Parties.event(c, id, "CREATED", today, draft.actorId(), null, null, null);

            Optional<Screening.Match> match = screening.screen(new Screening.Subject(
                id, draft.displayName(), draft.birthOrRegistrationDate(), draft.countryCode(),
                draft.identifiers()));
            if (match.isPresent()) {
                String detail = "filtrage : " + match.get().list() + " / " + match.get().reference()
                                + " — " + match.get().detail();
                Parties.updateStatus(c, id, PartyStatus.BLOCKED, detail);
                Parties.event(c, id, "SCREENING_MATCH", today, draft.actorId(), null, detail, null);
            }
            return id;
        });
    }

    /**
     * Verifie la connaissance client. La notation du risque commande le niveau de diligence et
     * l'echeance de revue : douze, vingt-quatre ou trente-six mois.
     */
    public void verifyKyc(UUID partyId, RiskRating rating, LocalDate verifiedOn, UUID actorId,
                          UUID approverId) {
        Objects.requireNonNull(rating, "rating");
        requireTwoPersons(actorId, approverId, "verification de la connaissance client");
        database.inTransaction(c -> {
            Party party = Parties.require(c, partyId);
            if (party.status() == PartyStatus.BLOCKED) {
                throw new PartyNotOperableException(party,
                    "un dossier bloque ne se verifie pas : lever le blocage d'abord");
            }
            Parties.updateKyc(c, partyId, KycStatus.VERIFIED, rating.level(), rating, verifiedOn,
                              verifiedOn.plusMonths(rating.reviewMonths()), approverId);
            Parties.event(c, partyId, "KYC_VERIFIED", verifiedOn, actorId, approverId,
                          rating + " — revue au " + verifiedOn.plusMonths(rating.reviewMonths()),
                          null);
            return null;
        });
    }

    public void block(UUID partyId, String reason, LocalDate on, UUID actorId, UUID approverId) {
        requireTwoPersons(actorId, approverId, "blocage d'un tiers");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Motif de blocage obligatoire");
        }
        database.inTransaction(c -> {
            Parties.require(c, partyId);
            Parties.updateStatus(c, partyId, PartyStatus.BLOCKED, reason);
            Parties.event(c, partyId, "BLOCKED", on, actorId, approverId, reason, null);
            return null;
        });
    }

    public void unblock(UUID partyId, String reason, LocalDate on, UUID actorId, UUID approverId) {
        requireTwoPersons(actorId, approverId, "levee du blocage d'un tiers");
        database.inTransaction(c -> {
            Party party = Parties.require(c, partyId);
            if (party.status() != PartyStatus.BLOCKED) {
                throw new IllegalStateException("Le tiers " + party.reference() + " n'est pas bloque");
            }
            Parties.updateStatus(c, partyId, PartyStatus.ACTIVE, null);
            Parties.event(c, partyId, "UNBLOCKED", on, actorId, approverId, reason, null);
            return null;
        });
    }

    public Party require(UUID partyId) {
        return database.inTransaction(c -> Parties.require(c, partyId));
    }

    // ------------------------------------------------------------------ exigences

    /** Le tiers existe et ses comptes peuvent fonctionner. */
    public static Party requireOperable(Connection c, UUID partyId) {
        Party party = Parties.require(c, partyId);
        if (!party.operable()) {
            throw new PartyNotOperableException(party,
                party.status() == PartyStatus.ACTIVE ? "connaissance client " + party.kycStatus()
                                                     : "dossier " + party.status()
                                                       + (party.statusReason() == null ? ""
                                                          : " — " + party.statusReason()));
        }
        return party;
    }

    /**
     * Le tiers existe, et un compte ou un credit peut lui etre ouvert.
     *
     * <p>Deux conditions, et la meme consequence : la connaissance client est a jour, et le
     * dossier est complet au regard de la politique de diligence declaree. Un dossier incomplet
     * — une piece manquante ou expiree, un beneficiaire effectif inconnu — n'arrete pas les
     * comptes existants ; il arrete ce qu'on allait ouvrir.
     */
    public static Party requireOnboardable(Connection c, UUID partyId) {
        Party party = requireOperable(c, partyId);
        if (!party.onboardable()) {
            throw new PartyNotOperableException(party,
                "connaissance client " + party.kycStatus() + " : rien ne s'ouvre sur un dossier "
                + "non verifie ou dont la revue est depassee. Ses comptes existants continuent de "
                + "fonctionner.");
        }
        PartyFile.Completeness completeness = PartyFile.completeness(
            c, partyId, businessDate(c, party.legalEntityId()));
        if (!completeness.complete()) {
            throw new PartyNotOperableException(party, "dossier incomplet — "
                + completeness.summary() + ". Ses comptes existants continuent de fonctionner.");
        }
        return party;
    }

    private static void requireTwoPersons(UUID actorId, UUID approverId, String what) {
        Objects.requireNonNull(actorId, "actorId");
        if (approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException(
                "La " + what + " se fait a deux : celui qui saisit n'est pas celui qui valide.");
        }
    }

    private static LocalDate businessDate(Connection c, UUID legalEntityId) {
        try (var ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (java.sql.SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
        }
    }

    /** Un tiers portant le meme identifiant officiel existe deja dans l'entite. */
    public static class DuplicatePartyException extends RuntimeException {
        public DuplicatePartyException(String detail, Throwable cause) {
            super("Doublon : " + detail, cause);
        }
    }

    /** Le tiers ne peut pas faire l'objet de l'operation demandee. */
    public static class PartyNotOperableException extends RuntimeException {
        public PartyNotOperableException(Party party, String detail) {
            super("Tiers " + party.reference() + " : " + detail);
        }
    }
}
