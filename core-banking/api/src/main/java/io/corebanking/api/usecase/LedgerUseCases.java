package io.corebanking.api.usecase;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Journal;
import io.corebanking.ledger.store.TrialBalance;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Restitutions comptables : la balance, ses totaux, le journal de l'entite. Lues dans le journal,
 * jamais dans un cliche ; reservees a la comptabilite et a l'audit ({@code LEDGER_READ}).
 */
public final class LedgerUseCases {

    private LedgerUseCases() {}

    /** Bornes d'une restitution : jusqu'a la date comptable, depuis le premier jour de son mois. */
    static LocalDate[] bounds(Connection c, UUID legalEntityId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : AccountUseCases.businessDate(c, legalEntityId);
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                "Plage de dates inversee : du " + start + " au " + end);
        }
        return new LocalDate[] {start, end};
    }

    // ------------------------------------------------------------------ balance

    /**
     * @param from premier jour de la plage ; a defaut le premier jour du mois de {@code to}
     * @param to   dernier jour ; a defaut la date comptable de l'entite
     * @param kind nature de compte, facultative : {@code CUSTOMER} pour la balance auxiliaire
     * @param branchId agence, facultative : la balance d'agence
     */
    public record BalanceQuery(UUID legalEntityId, LocalDate from, LocalDate to, AccountKind kind,
                               UUID branchId, Paging.PageRequest page) {}

    public record TotalsQuery(UUID legalEntityId, LocalDate from, LocalDate to, AccountKind kind,
                              UUID branchId) {}

    /** Une ligne de la balance, six colonnes ; un solde n'occupe qu'une colonne. */
    public record BalanceLine(UUID accountId, String code, AccountKind kind, AccountNature nature,
                              NormalBalance normalBalance, String currency,
                              Money openingDebit, Money openingCredit,
                              Money movementDebit, Money movementCredit,
                              Money closingDebit, Money closingCredit) {
        static BalanceLine of(TrialBalance.Line line) {
            return new BalanceLine(line.accountId(), line.code(), line.kind(), line.nature(),
                                   line.normalBalance(), line.currency().code(),
                                   line.openingDebit(), line.openingCredit(),
                                   line.movementDebit(), line.movementCredit(),
                                   line.closingDebit(), line.closingCredit());
        }
    }

    /** Les totaux d'une devise, et le constat qu'ils s'equilibrent — ou non, sur une balance filtree. */
    public record BalanceTotals(String currency, long accounts,
                                Money openingDebit, Money openingCredit,
                                Money movementDebit, Money movementCredit,
                                Money closingDebit, Money closingCredit, boolean balanced) {
        static BalanceTotals of(TrialBalance.Totals totals) {
            return new BalanceTotals(totals.currency().code(), totals.accounts(),
                                     totals.openingDebit(), totals.openingCredit(),
                                     totals.movementDebit(), totals.movementCredit(),
                                     totals.closingDebit(), totals.closingCredit(),
                                     totals.balanced());
        }
    }

    private static AccessTarget target(UUID legalEntityId, UUID branchId) {
        return branchId == null ? AccessTarget.inEntity(legalEntityId)
                                : AccessTarget.inBranch(legalEntityId, branchId);
    }

    public static final class ReadTrialBalance
            implements UseCase<BalanceQuery, Paging.Paged<BalanceLine>> {
        private final Database database;

        public ReadTrialBalance(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LEDGER_READ; }

        @Override
        public AccessTarget targetOf(BalanceQuery query) {
            return target(query.legalEntityId(), query.branchId());
        }

        @Override
        public Paging.Paged<BalanceLine> execute(BalanceQuery query) {
            return database.inTransaction(c -> {
                LocalDate[] range = bounds(c, query.legalEntityId(), query.from(), query.to());
                var filter = new TrialBalance.Filter(query.kind(), query.branchId());
                List<BalanceLine> lines = TrialBalance.page(
                        c, query.legalEntityId(), range[0], range[1], filter,
                        query.page().offset(), query.page().size())
                    .stream().map(BalanceLine::of).toList();
                long total = TrialBalance.count(c, query.legalEntityId(), range[0], range[1],
                                                filter);
                return new Paging.Paged<>(lines, query.page(), total);
            });
        }
    }

    public static final class ReadTrialBalanceTotals
            implements UseCase<TotalsQuery, List<BalanceTotals>> {
        private final Database database;

        public ReadTrialBalanceTotals(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LEDGER_READ; }

        @Override
        public AccessTarget targetOf(TotalsQuery query) {
            return target(query.legalEntityId(), query.branchId());
        }

        @Override
        public List<BalanceTotals> execute(TotalsQuery query) {
            return database.inTransaction(c -> {
                LocalDate[] range = bounds(c, query.legalEntityId(), query.from(), query.to());
                return TrialBalance.totals(c, query.legalEntityId(), range[0], range[1],
                                           new TrialBalance.Filter(query.kind(), query.branchId()))
                    .stream().map(BalanceTotals::of).toList();
            });
        }
    }

    // ------------------------------------------------------------------ journal

    /**
     * @param from premier jour ; a defaut {@code to} — le journal se lit jour par jour
     * @param to   dernier jour ; a defaut la date comptable de l'entite
     */
    public record JournalQuery(UUID legalEntityId, LocalDate from, LocalDate to,
                               Paging.CursorRequest cursor) {}

    /** Le journal de l'entite, toutes lignes, par curseur : la lecture des extractions. */
    public static final class ReadJournal
            implements UseCase<JournalQuery, Paging.Slice<Journal.StatementLine>> {
        private final Database database;

        public ReadJournal(Database database) {
            this.database = database;
        }

        @Override public Operation operation() { return Operation.LEDGER_READ; }

        @Override
        public AccessTarget targetOf(JournalQuery query) {
            return AccessTarget.inEntity(query.legalEntityId());
        }

        @Override
        public Paging.Slice<Journal.StatementLine> execute(JournalQuery query) {
            Journal.Position after = JournalCursor.decode(query.cursor().after());
            return database.inTransaction(c -> {
                LocalDate to = query.to() != null ? query.to()
                    : AccountUseCases.businessDate(c, query.legalEntityId());
                LocalDate from = query.from() != null ? query.from() : to;
                if (from.isAfter(to)) {
                    throw new IllegalArgumentException(
                        "Plage de dates inversee : du " + from + " au " + to);
                }
                List<Journal.StatementLine> fetched = Journal.journalAfter(
                    c, query.legalEntityId(), from, to, after, query.cursor().size() + 1);
                return Paging.Slice.of(fetched, query.cursor(),
                                       line -> JournalCursor.encode(line.position()));
            });
        }
    }
}
