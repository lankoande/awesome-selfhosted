package io.corebanking.api.usecase;

import io.corebanking.api.config.EodEngines;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import io.corebanking.tfj.RunMode;
import io.corebanking.tfj.TfjRun;
import java.time.LocalDate;
import java.util.UUID;

/** Traitement de fin de journee : lancement, reprise, annulation, consultation. */
public final class EodUseCases {

    private EodUseCases() {}

    public record Launch(UUID legalEntityId, LocalDate businessDate, RunMode mode, UUID actorId) {}

    public static final class Run implements UseCase<Launch, TfjRun> {
        private final EodEngines engines;

        public Run(EodEngines engines) {
            this.engines = engines;
        }

        @Override public Operation operation() { return Operation.TFJ_RUN; }

        @Override
        public AccessTarget targetOf(Launch command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public TfjRun execute(Launch command) {
            return engines.forEntity(command.legalEntityId())
                .run(command.legalEntityId(), command.businessDate(), command.actorId(),
                     command.mode());
        }
    }

    public record Resumption(UUID legalEntityId, UUID runId, UUID actorId) {}

    public static final class Resume implements UseCase<Resumption, TfjRun> {
        private final EodEngines engines;

        public Resume(EodEngines engines) {
            this.engines = engines;
        }

        @Override public Operation operation() { return Operation.TFJ_RUN; }

        @Override
        public AccessTarget targetOf(Resumption command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public TfjRun execute(Resumption command) {
            return engines.forEntity(command.legalEntityId()).resume(command.runId(),
                                                                     command.actorId());
        }
    }

    public record Cancellation(UUID legalEntityId, UUID runId, LocalDate reversalBookingDate,
                               String reason, UUID actorId) {}

    public static final class Cancel implements UseCase<Cancellation, TfjRun> {
        private final EodEngines engines;

        public Cancel(EodEngines engines) {
            this.engines = engines;
        }

        @Override public Operation operation() { return Operation.TFJ_CANCEL; }

        @Override
        public AccessTarget targetOf(Cancellation command) {
            return AccessTarget.inEntity(command.legalEntityId());
        }

        @Override
        public TfjRun execute(Cancellation command) {
            return engines.forEntity(command.legalEntityId())
                .cancel(command.runId(), command.actorId(), command.reversalBookingDate(),
                        command.reason());
        }
    }

    public record Lookup(UUID legalEntityId, UUID runId) {}

    public static final class Read implements UseCase<Lookup, TfjRun> {
        private final EodEngines engines;

        public Read(EodEngines engines) {
            this.engines = engines;
        }

        @Override public Operation operation() { return Operation.TFJ_RUN; }

        @Override
        public AccessTarget targetOf(Lookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public TfjRun execute(Lookup query) {
            return engines.forEntity(query.legalEntityId()).find(query.runId())
                .filter(run -> run.legalEntityId().equals(query.legalEntityId()))
                .orElseThrow(() -> new UnknownRunException(query.runId()));
        }
    }

    public static class UnknownRunException extends RuntimeException {
        public UnknownRunException(UUID runId) {
            super("Traitement inconnu : " + runId);
        }
    }
}
