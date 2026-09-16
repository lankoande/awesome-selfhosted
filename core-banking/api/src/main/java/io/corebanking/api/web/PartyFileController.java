package io.corebanking.api.web;

import io.corebanking.api.usecase.PartyFileUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.party.BeneficialOwners;
import io.corebanking.party.DocumentKind;
import io.corebanking.party.KycLevel;
import io.corebanking.party.KycPolicies;
import io.corebanking.party.PartyDocuments;
import io.corebanking.party.PartyFile;
import io.corebanking.party.PartyKind;
import io.corebanking.party.Relationships;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dossier client : pieces, relations, beneficiaires effectifs, politique de diligence.
 *
 * <p>Une piece se depose au guichet et remplace celle de sa nature sans l'effacer. Relations et
 * beneficiaires effectifs se declarent a deux : l'une donne un pouvoir sur des comptes, l'autre
 * est une declaration reglementaire. La completude du dossier se lit, et c'est elle qui dit
 * pourquoi une ouverture est refusee.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class PartyFileController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final PartyFileUseCases.DepositDocument depositDocument;
    private final PartyFileUseCases.ReadDocuments readDocuments;
    private final PartyFileUseCases.ReadRelationships readRelationships;
    private final PartyFileUseCases.ReadOwners readOwners;
    private final PartyFileUseCases.ReadCompleteness readCompleteness;
    private final PartyFileUseCases.ReadPolicies readPolicies;
    private final PartyFileUseCases.ReadIncompleteFiles readIncomplete;

    public PartyFileController(UseCaseExecutor executor, Database database,
                               MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.depositDocument = new PartyFileUseCases.DepositDocument(database);
        this.readDocuments = new PartyFileUseCases.ReadDocuments(database);
        this.readRelationships = new PartyFileUseCases.ReadRelationships(database);
        this.readOwners = new PartyFileUseCases.ReadOwners(database);
        this.readCompleteness = new PartyFileUseCases.ReadCompleteness(database);
        this.readPolicies = new PartyFileUseCases.ReadPolicies(database);
        this.readIncomplete = new PartyFileUseCases.ReadIncompleteFiles(database);
    }

    // ------------------------------------------------------------------ pieces

    /** Depose une piece ; celle de la meme nature est remplacee, et reste au dossier. */
    @PostMapping("/parties/{partyId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyDocuments.Document deposit(Caller caller, @PathVariable UUID legalEntityId,
                                           @PathVariable UUID partyId,
                                           @RequestBody Requests.PartyDocumentRequest body) {
        return executor.run(caller, depositDocument, new PartyDocuments.Deposit(
            legalEntityId, partyId, documentKind(body.kind()), body.reference(), body.issuer(),
            body.issuedOn(), body.expiresOn(),
            body.collectedOn() == null ? LocalDate.now() : body.collectedOn(),
            Callers.actorId(caller)));
    }

    @GetMapping("/parties/{partyId}/documents")
    public List<PartyDocuments.Document> documents(Caller caller, @PathVariable UUID legalEntityId,
                                                   @PathVariable UUID partyId) {
        return executor.run(caller, readDocuments,
                            new PartyFileUseCases.PartyQuery(legalEntityId, partyId));
    }

    /** Ce qui manque au dossier, au regard de la politique de diligence declaree. */
    @GetMapping("/parties/{partyId}/file")
    public Dossier file(Caller caller, @PathVariable UUID legalEntityId,
                        @PathVariable UUID partyId) {
        return Dossier.of(executor.run(caller, readCompleteness,
                                       new PartyFileUseCases.PartyQuery(legalEntityId, partyId)));
    }

    @GetMapping("/parties/incomplete-files")
    public List<Dossier> incomplete(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readIncomplete,
                            new PartyFileUseCases.EntityQuery(legalEntityId))
            .stream().map(Dossier::of).toList();
    }

    /**
     * La completude telle qu'elle se publie : le verdict et sa phrase, que le domaine calcule,
     * sont des champs de la reponse — l'agence lit la raison du refus sans la reconstituer.
     */
    public record Dossier(UUID partyId, String reference, PartyKind kind, KycLevel level,
                          boolean policyDeclared, List<DocumentKind> missing,
                          List<DocumentKind> expired, boolean beneficialOwnersMissing,
                          List<String> unverifiedOwners, boolean complete, String summary) {

        static Dossier of(PartyFile.Completeness c) {
            return new Dossier(c.partyId(), c.reference(), c.kind(), c.level(), c.policyDeclared(),
                               c.missing(), c.expired(), c.beneficialOwnersMissing(),
                               c.unverifiedOwners(), c.complete(), c.summary());
        }
    }

    // ------------------------------------------------------------------ relations

    @PostMapping("/parties/{partyId}/relationships")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareRelationship(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID partyId,
            @RequestBody Requests.RelationshipRequest body) {
        if (body.validFrom() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
        }
        return makerChecker.submit(caller, legalEntityId, "RELATIONSHIP_DECLARE", Payloads.of(
            "fromPartyId", partyId, "toPartyId", body.toPartyId(), "kind", body.kind(),
            "validFrom", body.validFrom()));
    }

    @GetMapping("/parties/{partyId}/relationships")
    public List<Relationships.Relationship> relationships(Caller caller,
                                                          @PathVariable UUID legalEntityId,
                                                          @PathVariable UUID partyId) {
        return executor.run(caller, readRelationships,
                            new PartyFileUseCases.PartyQuery(legalEntityId, partyId));
    }

    @PostMapping("/relationships/{relationshipId}/termination")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View endRelationship(Caller caller, @PathVariable UUID legalEntityId,
                                             @PathVariable UUID relationshipId,
                                             @RequestBody Requests.Termination body) {
        return makerChecker.submit(caller, legalEntityId, "RELATIONSHIP_END",
                                   Payloads.of("relationshipId", relationshipId,
                                               "endedOn", body.endedOn()));
    }

    // ------------------------------------------------------------------ beneficiaires effectifs

    @PostMapping("/parties/{partyId}/beneficial-owners")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareOwner(Caller caller, @PathVariable UUID legalEntityId,
                                          @PathVariable UUID partyId,
                                          @RequestBody Requests.BeneficialOwnerRequest body) {
        if (body.ownershipPercent() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : ownershipPercent");
        }
        return makerChecker.submit(caller, legalEntityId, "BENEFICIAL_OWNER_DECLARE", Payloads.of(
            "partyId", partyId, "ownerPartyId", body.ownerPartyId(),
            "ownershipPercent", body.ownershipPercent().toPlainString(),
            "declaredOn", body.declaredOn()));
    }

    @GetMapping("/parties/{partyId}/beneficial-owners")
    public List<BeneficialOwners.Owner> owners(Caller caller, @PathVariable UUID legalEntityId,
                                               @PathVariable UUID partyId) {
        return executor.run(caller, readOwners,
                            new PartyFileUseCases.PartyQuery(legalEntityId, partyId));
    }

    @PostMapping("/beneficial-owners/{ownerId}/termination")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View endOwner(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID ownerId,
                                      @RequestBody Requests.Termination body) {
        return makerChecker.submit(caller, legalEntityId, "BENEFICIAL_OWNER_END",
                                   Payloads.of("ownerId", ownerId, "endedOn", body.endedOn()));
    }

    // ------------------------------------------------------------------ politique de diligence

    /** La politique d'une nature de tiers et d'un niveau : elle remplace la precedente. */
    @PostMapping("/kyc-policies")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View setPolicy(Caller caller, @PathVariable UUID legalEntityId,
                                       @RequestBody Requests.KycPolicyRequest body) {
        return makerChecker.submit(caller, legalEntityId, "KYC_POLICY_SET", Payloads.of(
            "partyKind", body.partyKind(), "kycLevel", body.kycLevel(),
            "requiredDocuments", String.join(",", body.requiredDocuments() == null
                ? List.<String>of() : body.requiredDocuments()),
            "beneficialOwnersRequired", String.valueOf(Boolean.TRUE.equals(
                body.beneficialOwnersRequired())),
            "ownershipThresholdPercent", body.ownershipThresholdPercent() == null ? null
                : body.ownershipThresholdPercent().toPlainString()));
    }

    @GetMapping("/kyc-policies")
    public List<KycPolicies.Policy> policies(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readPolicies,
                            new PartyFileUseCases.EntityQuery(legalEntityId));
    }

    private static DocumentKind documentKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : kind");
        }
        try {
            return DocumentKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nature de piece inconnue : " + kind + " ("
                + java.util.Arrays.toString(DocumentKind.values()) + ")");
        }
    }
}
