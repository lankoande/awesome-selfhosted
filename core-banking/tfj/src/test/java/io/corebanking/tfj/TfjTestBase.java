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
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.product.ProductCatalog;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
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

        SchemaMigrator.migrate(database);
        applyScript("/db/V3__product.sql");
        applyScript("/db/V2__interest.sql");
        applyScript("/db/V6__tfj.sql");
        SchemaMigrator.ensurePartitions(database, J1.minusMonths(1), J1.plusMonths(2));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, J1);
            Entities.openPeriod(c, ENTITY, J1.withDayOfMonth(1),
                                J1.withDayOfMonth(1).plusMonths(2).minusDays(1));
            return null;
        });

        postingService = new JdbcPostingService(database);
        interestService = new BatchInterestAccrualService(database, postingService);
        engine = StandardTfj.engine(database, postingService, interestService);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    private static void applyScript(String resource) {
        String sql;
        try (InputStream in = TfjTestBase.class.getResourceAsStream(resource)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LedgerStoreException("Lecture de " + resource, e);
        }
        database.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("Application de " + resource, e);
            }
        });
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
