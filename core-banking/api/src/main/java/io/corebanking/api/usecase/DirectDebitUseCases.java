package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.DirectDebitService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Prelevements. Le mandat s'enregistre a deux ({@code DualControlHandlers}) et se revoque par le
 * gestionnaire du compte ; un prelevement recu est presente par la compensation ou un creancier
 * de la banque ; la remise d'un prelevement emis est plafonnee par role ; le suivi est du
 * back-office ; la lecture est tracee.
 */
public final class DirectDebitUseCases {

    private DirectDebitUseCases() {}

    public record Revocation(UUID legalEntityId, UUID mandateId, String reason, UUID actorId) {}

    public static final class Revoke implements UseCase<Revocation, DirectDebitService.Mandate> {
        private final DirectDebitService directDebits;

        public Revoke(DirectDebitService directDebits) {
            this.directDebits = directDebits;
        }

        @Override public Operation operation() { return Operation.MANDATE_REVOKE; }

        @Override
        public AccessTarget targetOf(Revocation revocation) {
            return AccessTarget.inEntity(revocation.legalEntityId());
        }

        @Override
        public DirectDebitService.Mandate execute(Revocation revocation) {
            return directDebits.revokeMandate(revocation.legalEntityId(), revocation.mandateId(),
                                              revocation.reason(), revocation.actorId());
        }
    }

    public static final class Present
            implements UseCase<DirectDebitService.Presentation, DirectDebitService.Presented> {
        private final DirectDebitService directDebits;

        public Present(DirectDebitService directDebits) {
            this.directDebits = directDebits;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_PRESENT; }

        @Override
        public AccessTarget targetOf(DirectDebitService.Presentation presentation) {
            return AccessTarget.inEntity(presentation.legalEntityId());
        }

        @Override
        public DirectDebitService.Presented execute(DirectDebitService.Presentation presentation) {
            return directDebits.present(presentation);
        }
    }

    public static final class Issue
            implements UseCase<DirectDebitService.Issue, DirectDebitService.Presented> {
        private final DirectDebitService directDebits;

        public Issue(DirectDebitService directDebits) {
            this.directDebits = directDebits;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_ISSUE; }

        @Override
        public AccessTarget targetOf(DirectDebitService.Issue issue) {
            return AccessTarget.inEntity(issue.legalEntityId()).withAmount(issue.amount());
        }

        @Override
        public DirectDebitService.Presented execute(DirectDebitService.Issue issue) {
            return directDebits.issue(issue);
        }
    }

    public enum Transition { SETTLE, CANCEL, REFUND, RETURN }

    /** @param nostroAccountId pour un reglement ; @param reason pour les autres transitions */
    public record Step(UUID legalEntityId, UUID directDebitId, Transition transition,
                       UUID nostroAccountId, String reason, UUID actorId) {}

    public static final class Process implements UseCase<Step, DirectDebitService.DirectDebit> {
        private final DirectDebitService directDebits;
        private final Database database;

        public Process(DirectDebitService directDebits, Database database) {
            this.directDebits = directDebits;
            this.database = database;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_PROCESS; }

        @Override
        public AccessTarget targetOf(Step step) {
            return AccessTarget.inEntity(
                require(database, step.legalEntityId(), step.directDebitId()).legalEntityId());
        }

        @Override
        public DirectDebitService.DirectDebit execute(Step step) {
            return switch (step.transition()) {
                case SETTLE -> {
                    if (step.nostroAccountId() == null) {
                        throw new IllegalArgumentException("Champ obligatoire absent : nostroAccountId");
                    }
                    yield directDebits.settle(step.directDebitId(), step.nostroAccountId(),
                                              step.actorId());
                }
                case CANCEL -> directDebits.cancel(step.directDebitId(), step.reason(), step.actorId());
                case REFUND -> directDebits.refund(step.directDebitId(), step.reason(), step.actorId());
                case RETURN -> directDebits.returnIssued(step.directDebitId(), step.reason(),
                                                         step.actorId());
            };
        }
    }

    // ------------------------------------------------------------------ lecture

    public record AccountQuery(UUID accountId) {}

    public static final class ReadMandates
            implements UseCase<AccountQuery, List<DirectDebitService.Mandate>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadMandates(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_READ; }

        @Override
        public AccessTarget targetOf(AccountQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public List<DirectDebitService.Mandate> execute(AccountQuery query) {
            return database.inTransaction(c -> DirectDebitService.mandates(c, query.accountId()));
        }
    }

    public static final class ReadAccountDebits
            implements UseCase<AccountQuery, List<DirectDebitService.DirectDebit>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadAccountDebits(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_READ; }

        @Override
        public AccessTarget targetOf(AccountQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public List<DirectDebitService.DirectDebit> execute(AccountQuery query) {
            return database.inTransaction(c -> DirectDebitService.ofAccount(c, query.accountId()));
        }
    }

    public record Lookup(UUID legalEntityId, UUID directDebitId) {}

    public static final class Read implements UseCase<Lookup, DirectDebitService.DirectDebit> {
        private final Database database;

        public Read(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_READ; }

        @Override
        public AccessTarget targetOf(Lookup query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public DirectDebitService.DirectDebit execute(Lookup query) {
            return require(database, query.legalEntityId(), query.directDebitId());
        }
    }

    public record Query(UUID legalEntityId, String direction, String status,
                        Paging.PageRequest page) {}

    public static final class List_
            implements UseCase<Query, Paging.Paged<DirectDebitService.DirectDebit>> {
        private final Database database;

        public List_(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.DIRECT_DEBIT_READ; }

        @Override
        public AccessTarget targetOf(Query query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Paged<DirectDebitService.DirectDebit> execute(Query query) {
            String status = normalise(query.status());
            DirectDebitService.DirectionKind direction = direction(query.direction());
            return database.inTransaction(c -> new Paging.Paged<>(
                DirectDebitService.page(c, query.legalEntityId(), direction, status,
                                        query.page().offset(), query.page().size()),
                query.page(),
                DirectDebitService.count(c, query.legalEntityId(), direction, status)));
        }
    }

    static DirectDebitService.DirectDebit require(Database database, UUID legalEntityId, UUID id) {
        return database.inTransaction(c -> DirectDebitService.find(c, id))
            .filter(dd -> dd.legalEntityId().equals(legalEntityId))
            .orElseThrow(() -> new DirectDebitService.UnknownDirectDebitException(id));
    }

    private static String normalise(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static DirectDebitService.DirectionKind direction(String value) {
        String normalised = normalise(value);
        if (normalised == null) {
            return null;
        }
        try {
            return DirectDebitService.DirectionKind.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Sens inconnu : " + value + " (RECEIVED ou ISSUED)");
        }
    }
}
