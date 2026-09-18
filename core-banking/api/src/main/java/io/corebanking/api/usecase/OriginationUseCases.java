package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Database;
import io.corebanking.loan.service.LendingPolicies;
import io.corebanking.loan.service.LoanOrigination;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.UUID;

/**
 * Origination : depot, instruction et conditions relevent de l'agence ; decider, lever une
 * condition et ecrire la politique d'octroi se decident a deux ({@code DualControlHandlers}).
 * Les lectures suivent le dossier de credit ({@code LOAN_READ}).
 */
public final class OriginationUseCases {

    private OriginationUseCases() {}

    public static final class SubmitApplication
            implements UseCase<LoanOrigination.Request, LoanOrigination.Application> {
        private final Database database;

        public SubmitApplication(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_APPLICATION; }

        @Override
        public AccessTarget targetOf(LoanOrigination.Request request) {
            return request.branchId() == null
                ? AccessTarget.inEntity(request.legalEntityId())
                : AccessTarget.inBranch(request.legalEntityId(), request.branchId());
        }

        @Override
        public LoanOrigination.Application execute(LoanOrigination.Request request) {
            return database.inTransaction(c -> LoanOrigination.submit(c, request));
        }
    }

    public static final class AssessApplication
            implements UseCase<LoanOrigination.Instruction, LoanOrigination.Assessment> {
        private final Database database;

        public AssessApplication(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_APPLICATION; }

        @Override
        public AccessTarget targetOf(LoanOrigination.Instruction instruction) {
            return AccessTarget.inEntity(application(database, instruction.applicationId())
                                             .legalEntityId());
        }

        @Override
        public LoanOrigination.Assessment execute(LoanOrigination.Instruction instruction) {
            return database.inTransaction(c -> LoanOrigination.assess(c, instruction));
        }
    }

    /** Une condition posee au dossier : c'est sa levee, pas sa pose, qui se decide a deux. */
    public record NewCondition(UUID legalEntityId, UUID applicationId,
                               LoanOrigination.ConditionKind kind, String description,
                               java.time.LocalDate dueOn, java.time.LocalDate on, UUID actorId) {}

    public static final class AddCondition
            implements UseCase<NewCondition, LoanOrigination.Condition> {
        private final Database database;

        public AddCondition(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_APPLICATION; }

        @Override
        public AccessTarget targetOf(NewCondition condition) {
            return AccessTarget.inEntity(
                require(database, condition.legalEntityId(), condition.applicationId())
                    .legalEntityId());
        }

        @Override
        public LoanOrigination.Condition execute(NewCondition condition) {
            return database.inTransaction(c -> LoanOrigination.addCondition(
                c, condition.applicationId(), condition.kind(), condition.description(),
                condition.dueOn(), condition.on(), condition.actorId()));
        }
    }

    /** Retrait d'une demande, avant qu'elle ne devienne contrat. */
    public record Withdrawal(UUID legalEntityId, UUID applicationId, java.time.LocalDate on,
                             String reason, UUID actorId) {}

    public static final class CancelApplication implements UseCase<Withdrawal, Object> {
        private final Database database;

        public CancelApplication(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_APPLICATION; }

        @Override
        public AccessTarget targetOf(Withdrawal withdrawal) {
            return AccessTarget.inEntity(
                require(database, withdrawal.legalEntityId(), withdrawal.applicationId())
                    .legalEntityId());
        }

        @Override
        public Object execute(Withdrawal withdrawal) {
            return database.inTransaction(c -> {
                LoanOrigination.cancel(c, withdrawal.applicationId(), withdrawal.on(),
                                       withdrawal.reason(), withdrawal.actorId());
                return LoanOrigination.require(c, withdrawal.applicationId());
            });
        }
    }

    /** Transformation d'un accord en contrat : le montant vient de la decision. */
    public record Contracting(UUID legalEntityId, LoanOrigination.Contracting contracting) {}

    /** Le contrat ne du dossier, tel que l'API le rend. */
    public record Contracted(UUID applicationId, UUID contractId, String reference) {}

    public static final class ContractApplication implements UseCase<Contracting, Contracted> {
        private final Database database;

        public ContractApplication(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_CONTRACT_CREATE; }

        @Override
        public AccessTarget targetOf(Contracting contracting) {
            return AccessTarget.inEntity(
                require(database, contracting.legalEntityId(),
                        contracting.contracting().applicationId()).legalEntityId());
        }

        @Override
        public Contracted execute(Contracting contracting) {
            UUID contractId = database.inTransaction(
                c -> LoanOrigination.contractualise(c, contracting.contracting()));
            return new Contracted(contracting.contracting().applicationId(), contractId,
                                  contracting.contracting().contractReference());
        }
    }

    // ------------------------------------------------------------------ lectures

    public record ApplicationQuery(UUID legalEntityId, UUID applicationId) {}

    /** Le dossier entier : la demande, ses instructions, sa decision, ses conditions, son journal. */
    public record File(LoanOrigination.Application application,
                       List<LoanOrigination.Assessment> assessments,
                       LoanOrigination.Decision decision,
                       List<LoanOrigination.Condition> conditions,
                       List<LoanOrigination.Event> events) {}

    public static final class ReadFile implements UseCase<ApplicationQuery, File> {
        private final Database database;

        public ReadFile(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_READ; }

        @Override
        public AccessTarget targetOf(ApplicationQuery query) {
            return AccessTarget.inEntity(
                require(database, query.legalEntityId(), query.applicationId()).legalEntityId());
        }

        @Override
        public File execute(ApplicationQuery query) {
            return database.inTransaction(c -> new File(
                LoanOrigination.require(c, query.applicationId()),
                LoanOrigination.assessments(c, query.applicationId()),
                LoanOrigination.decision(c, query.applicationId()).orElse(null),
                LoanOrigination.conditions(c, query.applicationId()),
                LoanOrigination.events(c, query.applicationId())));
        }
    }

    public record EntityQuery(UUID legalEntityId, LoanOrigination.Status status) {}

    /** @param page la page demandee ; le statut reste un filtre facultatif */
    public record ApplicationsQuery(UUID legalEntityId, LoanOrigination.Status status,
                                    Paging.PageRequest page) {}

    public static final class ReadApplications
            implements UseCase<ApplicationsQuery, Paging.Paged<LoanOrigination.Application>> {
        private final Database database;

        public ReadApplications(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_READ; }

        @Override
        public AccessTarget targetOf(ApplicationsQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Paged<LoanOrigination.Application> execute(ApplicationsQuery query) {
            return database.inTransaction(c -> new Paging.Paged<>(
                LoanOrigination.applications(c, query.legalEntityId(), query.status(),
                                             query.page().offset(), query.page().size()),
                query.page(),
                LoanOrigination.countApplications(c, query.legalEntityId(), query.status())));
        }
    }

    public static final class ReadPolicies
            implements UseCase<EntityQuery, List<LendingPolicies.Policy>> {
        private final Database database;

        public ReadPolicies(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LOAN_READ; }

        @Override
        public AccessTarget targetOf(EntityQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public List<LendingPolicies.Policy> execute(EntityQuery query) {
            return database.inTransaction(c -> LendingPolicies.all(c, query.legalEntityId()));
        }
    }

    static LoanOrigination.Application application(Database database, UUID applicationId) {
        return database.inTransaction(c -> LoanOrigination.require(c, applicationId));
    }

    /** Le dossier releve de l'entite de l'appelant, ou il n'existe pas. */
    static LoanOrigination.Application require(Database database, UUID legalEntityId,
                                               UUID applicationId) {
        LoanOrigination.Application application = application(database, applicationId);
        if (!application.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException("Demande de credit inconnue : " + applicationId);
        }
        return application;
    }
}
