package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.Holds;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cycle de vie et consultation d'un compte. La cible d'un acte de gestion — ouverture, cloture,
 * blocage — est l'agence gestionnaire du compte : ces actes ne se deplacent pas. La lecture d'un
 * solde se deplace, et elle est tracee.
 */
public final class AccountUseCases {

    private AccountUseCases() {}

    public static final class Open implements UseCase<AccountLifecycle.Opening, UUID> {
        private final AccountLifecycle lifecycle;

        public Open(AccountLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_OPEN; }

        @Override
        public AccessTarget targetOf(AccountLifecycle.Opening command) {
            return AccessTarget.inBranch(command.legalEntityId(), command.branchId());
        }

        @Override
        public UUID execute(AccountLifecycle.Opening command) {
            return lifecycle.open(command);
        }
    }

    public static final class Close
            implements UseCase<AccountLifecycle.Closing, AccountLifecycle.Closure> {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        public Close(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_CLOSE; }

        @Override
        public AccessTarget targetOf(AccountLifecycle.Closing command) {
            Account account = accounts.require(command.accountId());
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public AccountLifecycle.Closure execute(AccountLifecycle.Closing command) {
            return lifecycle.close(command);
        }
    }

    public static final class Block implements UseCase<AccountLifecycle.Block, UUID> {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        public Block(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_BLOCK; }

        @Override
        public AccessTarget targetOf(AccountLifecycle.Block command) {
            Account account = accounts.require(command.accountId());
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public UUID execute(AccountLifecycle.Block command) {
            return lifecycle.block(command);
        }
    }

    /** Levee d'un blocage de compte. */
    public record Lift(UUID accountId, UUID blockId, String reason, UUID actorId, UUID approverId) {}

    public static final class Unblock implements UseCase<Lift, Void> {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        public Unblock(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_BLOCK; }

        @Override
        public AccessTarget targetOf(Lift command) {
            Account account = accounts.require(command.accountId());
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Void execute(Lift command) {
            lifecycle.unblock(command.blockId(), command.reason(), command.actorId(),
                              command.approverId());
            return null;
        }
    }

    public static final class PlaceHold implements UseCase<Holds.Placement, UUID> {
        private final Database database;
        private final AccountDirectory accounts;

        public PlaceHold(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_HOLD; }

        @Override
        public AccessTarget targetOf(Holds.Placement command) {
            return AccessTarget.inEntity(accounts.require(command.accountId()).legalEntityId())
                .withAmount(command.amount());
        }

        @Override
        public UUID execute(Holds.Placement command) {
            return database.inTransaction(c -> Holds.place(c, command));
        }
    }

    /** Levee d'un blocage de montant. */
    public record Release(UUID accountId, UUID holdId, LocalDate on, UUID actorId) {}

    public static final class ReleaseHold implements UseCase<Release, Void> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReleaseHold(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_HOLD; }

        @Override
        public AccessTarget targetOf(Release command) {
            return AccessTarget.inEntity(accounts.require(command.accountId()).legalEntityId());
        }

        @Override
        public Void execute(Release command) {
            database.inTransaction(c -> {
                Holds.release(c, command.holdId(), command.on(), command.actorId());
                return null;
            });
            return null;
        }
    }

    /** Lecture d'un solde ; l'agence de l'appelant dit si la lecture est deplacee. */
    public record BalanceQuery(UUID accountId, UUID callerBranchId) {}

    public record Balance(UUID accountId, String code, String currency, Money current,
                          Money available, LocalDate asOf, UUID branchId, String status) {}

    public static final class ReadBalance implements UseCase<BalanceQuery, Balance> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadBalance(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_BALANCE_READ; }

        @Override
        public AccessTarget targetOf(BalanceQuery query) {
            Account account = accounts.require(query.accountId());
            AccessTarget target = AccessTarget.inBranch(account.legalEntityId(), account.branchId());
            boolean remote = account.branchId() != null
                             && !account.branchId().equals(query.callerBranchId());
            return remote ? target.performedRemotely() : target;
        }

        @Override
        public Balance execute(BalanceQuery query) {
            Account account = accounts.require(query.accountId());
            return database.inTransaction(c -> {
                LocalDate asOf = businessDate(c, account.legalEntityId());
                return new Balance(account.id(), account.code(), account.currency().code(),
                                   Balances.current(c, account.id()),
                                   Balances.available(c, account.id(), asOf), asOf,
                                   account.branchId(), account.status().name());
            });
        }
    }

    /** Une devise du referentiel, par son code : scale et arrondi viennent de la base, pas du client. */
    public static io.corebanking.kernel.money.CurrencyRef currency(java.sql.Connection c,
                                                                   String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Devise obligatoire");
        }
        try (var ps = c.prepareStatement(
            "SELECT code, scale, rounding_mode FROM currency WHERE code = ?")) {
            ps.setString(1, code);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Devise inconnue du referentiel : " + code);
                }
                return new io.corebanking.kernel.money.CurrencyRef(
                    rs.getString(1), rs.getInt(2), java.math.RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("Devise " + code, e);
        }
    }

    /** La date comptable courante de l'entite : celle a laquelle un disponible se lit. */
    public static LocalDate businessDate(java.sql.Connection c, UUID legalEntityId) {
        try (var ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
        }
    }

    // ------------------------------------------------------------------ releve de compte

    /**
     * @param from premiere date comptable, a defaut un mois avant {@code to}
     * @param to   derniere date comptable, a defaut la date comptable de l'entite
     */
    public record JournalQuery(UUID accountId, LocalDate from, LocalDate to,
                               Paging.PageRequest page) {}

    /** Les mouvements d'un compte, par pages, dans l'ordre du journal ; la lecture est tracee. */
    public static final class ReadJournal
            implements UseCase<JournalQuery, Paging.Paged<io.corebanking.ledger.store.Journal.StatementLine>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadJournal(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_JOURNAL_READ; }

        @Override
        public AccessTarget targetOf(JournalQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public Paging.Paged<io.corebanking.ledger.store.Journal.StatementLine> execute(
                JournalQuery query) {
            Account account = accounts.require(query.accountId());
            return database.inTransaction(c -> {
                LocalDate to = query.to() != null ? query.to()
                    : businessDate(c, account.legalEntityId());
                LocalDate from = query.from() != null ? query.from() : to.minusMonths(1);
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException(
                        "Plage de dates inversee : du " + from + " au " + to);
                }
                var lines = io.corebanking.ledger.store.Journal.statement(
                    c, account.id(), from, to, query.page().offset(), query.page().size());
                long total = io.corebanking.ledger.store.Journal.countStatement(
                    c, account.id(), from, to);
                return new Paging.Paged<>(lines, query.page(), total);
            });
        }
    }

    // ------------------------------------------------------------------ grand livre du compte

    /** Le meme releve, par curseur : pour un compte chaud sur des annees, sans decompte. */
    public record LedgerQuery(UUID accountId, LocalDate from, LocalDate to,
                              Paging.CursorRequest cursor) {}

    public static final class ReadLedger
            implements UseCase<LedgerQuery, Paging.Slice<io.corebanking.ledger.store.Journal.StatementLine>> {
        private final Database database;
        private final AccountDirectory accounts;

        public ReadLedger(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public Operation operation() { return Operation.ACCOUNT_JOURNAL_READ; }

        @Override
        public AccessTarget targetOf(LedgerQuery query) {
            return AccessTarget.inEntity(accounts.require(query.accountId()).legalEntityId());
        }

        @Override
        public Paging.Slice<io.corebanking.ledger.store.Journal.StatementLine> execute(
                LedgerQuery query) {
            Account account = accounts.require(query.accountId());
            io.corebanking.ledger.store.Journal.Position after =
                JournalCursor.decode(query.cursor().after());
            return database.inTransaction(c -> {
                LocalDate to = query.to() != null ? query.to()
                    : businessDate(c, account.legalEntityId());
                LocalDate from = query.from() != null ? query.from() : to.minusMonths(1);
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException(
                        "Plage de dates inversee : du " + from + " au " + to);
                }
                var fetched = io.corebanking.ledger.store.Journal.statementAfter(
                    c, account.id(), from, to, after, query.cursor().size() + 1);
                return Paging.Slice.of(fetched, query.cursor(),
                                       line -> JournalCursor.encode(line.position()));
            });
        }
    }
}
