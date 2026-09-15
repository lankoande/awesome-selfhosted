package io.corebanking.api.usecase;

import io.corebanking.party.Party;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.time.LocalDate;
import java.util.UUID;

/** Referentiel client : creation, verification de la connaissance client, consultation. */
public final class PartyUseCases {

    private PartyUseCases() {}

    public static final class Create implements UseCase<PartyService.Draft, UUID> {
        private final PartyService parties;

        public Create(PartyService parties) {
            this.parties = parties;
        }

        @Override public Operation operation() { return Operation.PARTY_CREATE; }

        @Override
        public AccessTarget targetOf(PartyService.Draft draft) {
            return AccessTarget.inEntity(draft.legalEntityId());
        }

        @Override
        public UUID execute(PartyService.Draft draft) {
            return parties.create(draft);
        }
    }

    public record Verification(UUID partyId, RiskRating rating, LocalDate verifiedOn, UUID actorId,
                               UUID approverId) {}

    public static final class VerifyKyc implements UseCase<Verification, Party> {
        private final PartyService parties;

        public VerifyKyc(PartyService parties) {
            this.parties = parties;
        }

        @Override public Operation operation() { return Operation.KYC_VERIFY; }

        @Override
        public AccessTarget targetOf(Verification command) {
            return AccessTarget.inEntity(parties.require(command.partyId()).legalEntityId());
        }

        @Override
        public Party execute(Verification command) {
            parties.verifyKyc(command.partyId(), command.rating(), command.verifiedOn(),
                              command.actorId(), command.approverId());
            return parties.require(command.partyId());
        }
    }

    public static final class Read implements UseCase<UUID, Party> {
        private final PartyService parties;

        public Read(PartyService parties) {
            this.parties = parties;
        }

        @Override public Operation operation() { return Operation.PARTY_READ; }

        @Override
        public AccessTarget targetOf(UUID partyId) {
            return AccessTarget.inEntity(parties.require(partyId).legalEntityId());
        }

        @Override
        public Party execute(UUID partyId) {
            return parties.require(partyId);
        }
    }
}
