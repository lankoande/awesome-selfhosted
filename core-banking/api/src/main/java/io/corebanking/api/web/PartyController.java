package io.corebanking.api.web;

import io.corebanking.api.usecase.PartyUseCases;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.Party;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Referentiel client. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/parties")
public class PartyController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final PartyUseCases.Create create;
    private final PartyUseCases.Read read;

    public PartyController(UseCaseExecutor executor, PartyService parties,
                           MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.create = new PartyUseCases.Create(parties);
        this.read = new PartyUseCases.Read(parties);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created create(Caller caller, @PathVariable UUID legalEntityId,
                                   @RequestBody Requests.CreateParty body) {
        List<PartyIdentifier> identifiers = body.identifiers() == null ? List.of()
            : body.identifiers().stream()
                .map(i -> new PartyIdentifier(IdentifierKind.valueOf(i.kind()), i.value(),
                                              i.issuedOn(), i.expiresOn(), i.issuer()))
                .toList();
        UUID id = executor.run(caller, create, new PartyService.Draft(
            legalEntityId, body.reference(), PartyKind.valueOf(body.kind()), body.displayName(),
            body.birthOrRegistrationDate(), body.countryCode(), body.segment(), identifiers,
            Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    /** La verification de la connaissance client se fait a deux : soumise, puis approuvee. */
    @PostMapping("/{partyId}/kyc-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View verify(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID partyId,
                                    @RequestBody Requests.VerifyKyc body) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("partyId", partyId.toString());
        payload.put("rating", body.rating());
        if (body.verifiedOn() != null) {
            payload.put("verifiedOn", body.verifiedOn().toString());
        }
        return makerChecker.submit(caller, legalEntityId, "KYC_VERIFY", payload);
    }

    @GetMapping("/{partyId}")
    public Party read(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID partyId) {
        return executor.run(caller, read, partyId);
    }
}
