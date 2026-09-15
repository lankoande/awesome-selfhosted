package io.corebanking.tfj;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.calendar.Calendars;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.product.ProductCatalog;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class TfjTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static BatchInterestAccrualService interestService;
    protected static io.corebanking.fee.service.FeeChargingService feeService;
    protected static io.corebanking.loan.service.LoanService loanService;
    protected static io.corebanking.loan.service.LoanLateChargesService lateService;
    protected static io.corebanking.loan.service.LoanClassificationService classificationService;
    protected static io.corebanking.loan.service.LoanMobilisationService mobilisationService;
    protected static BusinessCalendar calendar;
    protected static TfjEngine engine;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    protected static final LocalDate J1 = LocalDate.of(2026, 9, 14);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);

        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, J1.minusMonths(1), J1.plusMonths(2));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, J1);
            Entities.openPeriod(c, ENTITY, J1.withDayOfMonth(1),
                                J1.withDayOfMonth(1).plusMonths(2).minusDays(1));
            return null;
        });

        UUID calendarId = database.inTransaction(c -> {
            UUID id = Calendars.createCalendar(c, "CI", "Cote d'Ivoire",
                java.util.Set.of(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY),
                J1.minusYears(1), J1.plusYears(2));
            Calendars.attachToEntity(c, ENTITY, id);
            return id;
        });
        calendar = Calendars.load(database, ENTITY).calendar();

        postingService = new JdbcPostingService(database);
        interestService = new BatchInterestAccrualService(database, postingService);
        feeService = new io.corebanking.fee.service.FeeChargingService(database, postingService);
        loanService = new io.corebanking.loan.service.LoanService(database, postingService);
        lateService = new io.corebanking.loan.service.LoanLateChargesService(database,
                                                                            postingService);
        classificationService = new io.corebanking.loan.service.LoanClassificationService(
            database, postingService, loanService);
        mobilisationService = new io.corebanking.loan.service.LoanMobilisationService(
            database, postingService);
        engine = StandardTfj.engine(database, postingService, interestService, feeService,
                                    loanService, mobilisationService, lateService,
                                    classificationService, calendar);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account account(String code, AccountKind kind, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    /** Compte d'epargne rattache a un produit remunere a 6 %. */
    protected static Account savingsAccount(String code, Account charges, Account accrued,
                                            String productCode) {
        Account client = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, productCode, "SAVINGS_ACCOUNT", "Epargne", "XOF", J1.minusMonths(1), null,
                Map.of(ProductCatalog.P_RATE, "6",
                       ProductCatalog.P_DAY_COUNT, "ACT_365",
                       ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name(),
                       ProductCatalog.P_CAPITALISATION, "QUARTERLY",
                       ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString(),
                       ProductCatalog.P_CREDIT_ACCOUNT, accrued.id().toString()),
                List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            ProductCatalog.assignProduct(c, client.id(), productCode, J1.minusMonths(1), null);
            return null;
        });
        return client;
    }

    protected static void deposit(Account client, Account cash, String amount, LocalDate valueDate,
                                  String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, valueDate, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(cash.id(), Money.of(amount, Currencies.XOF), valueDate, null),
                    PostingLine.credit(client.id(), Money.of(amount, Currencies.XOF), valueDate,
                                       null))));
    }

    protected static LocalDate businessDate() {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, ENTITY);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, LocalDate.class);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de la date comptable", e);
            }
        });
    }

    protected static long countEntries(UUID runId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM journal_entry WHERE batch_run_id = ?")) {
                ps.setObject(1, runId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des ecritures du TFJ", e);
            }
        });
    }
}
