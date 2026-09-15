package io.corebanking.fee.service;

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
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class FeeTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static FeeChargingService feeService;

    /** Comptes d'interets du compte courant non remunere : le taux est nul, pas le parametrage. */
    protected static Account chargesInterets;
    protected static Account interetsCourus;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    protected static final LocalDate OUVERTURE = LocalDate.of(2026, 9, 1);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);

        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, OUVERTURE.minusMonths(2), OUVERTURE.plusMonths(12));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI", Currencies.XOF,
                                       OUVERTURE);
            Entities.openPeriod(c, ENTITY, OUVERTURE.minusMonths(2),
                                OUVERTURE.plusMonths(12));
            return null;
        });

        postingService = new JdbcPostingService(database);
        feeService = new FeeChargingService(database, postingService);
        chargesInterets = glDebit("GL-INT-CHARGES");
        interetsCourus = gl("GL-INT-COURUS");
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account account(String code, AccountKind kind, NormalBalance normalBalance,
                                     LocalDate openedAt) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, openedAt);
            return null;
        });
        return account;
    }

    protected static Account gl(String code) {
        return account(code, AccountKind.GL, NormalBalance.CREDIT, OUVERTURE);
    }

    protected static Account glDebit(String code) {
        return account(code, AccountKind.GL, NormalBalance.DEBIT, OUVERTURE);
    }

    protected static Account client(String code, LocalDate openedAt) {
        return account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT, openedAt);
    }

    /**
     * Compte courant non remunere.
     *
     * <p>Le taux nul est <b>explicite</b>, et les comptes d'imputation designes : le traitement de
     * fin de journee remunere tout compte rattache a un produit, et sa famille exige donc ces
     * parametres. Les omettre ferait echouer une etape bloquante la nuit suivante — c'est ce que la
     * famille de produit interdit desormais de deployer.
     */
    protected static UUID product(String code, Map<String, String> parameters,
                                  LocalDate validFrom) {
        Map<String, String> complets = new java.util.LinkedHashMap<>(parameters);
        complets.putIfAbsent(ProductCatalog.P_RATE, "0");
        complets.putIfAbsent(ProductCatalog.P_DAY_COUNT, "ACT_365");
        complets.putIfAbsent(ProductCatalog.P_SIDE, "CREDITOR");
        complets.putIfAbsent(ProductCatalog.P_CAPITALISATION, "MONTHLY");
        complets.putIfAbsent(ProductCatalog.P_DEBIT_ACCOUNT, chargesInterets.id().toString());
        complets.putIfAbsent(ProductCatalog.P_CREDIT_ACCOUNT, interetsCourus.id().toString());
        return database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, "CURRENT_ACCOUNT", "Compte courant", "XOF", validFrom, null,
                complets, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return version;
        });
    }

    protected static void assign(Account account, String productCode, LocalDate from) {
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, account.id(), productCode, from, null);
            return null;
        });
    }

    protected static void credit(Account client, Account counterpart, String amount,
                                 LocalDate valueDate, String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, valueDate, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(counterpart.id(), Money.of(amount, Currencies.XOF), valueDate,
                                      null),
                    PostingLine.credit(client.id(), Money.of(amount, Currencies.XOF), valueDate,
                                       null))));
    }

    protected static void debit(Account client, Account counterpart, String amount,
                                LocalDate valueDate, String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, valueDate, "WITHDRAWAL", ACTOR,
            List.of(PostingLine.debit(client.id(), Money.of(amount, Currencies.XOF), valueDate,
                                      null),
                    PostingLine.credit(counterpart.id(), Money.of(amount, Currencies.XOF),
                                       valueDate, null))));
    }

    /** Ce qu'une assertion a besoin de savoir d'une commission liquidee. */
    protected record Charged(String feeCode, LocalDate periodStart, LocalDate periodEnd,
                             LocalDate chargeDate, Money basis, Money net, Money tax, Money total,
                             FeeOutcome outcome, UUID entryId, int attempts, int generation,
                             int chargedDays) {}

    protected static List<Charged> charges(UUID accountId) {
        return database.inTransaction(c -> {
            List<Charged> found = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT fee_code, period_start, period_end, charge_date, basis_amount, net_amount,"
                + " tax_amount, total_amount, outcome, entry_id, attempts, generation, charged_days"
                + " FROM fee_charge WHERE account_id = ? ORDER BY period_end, fee_code")) {
                ps.setObject(1, accountId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(new Charged(
                            rs.getString(1), rs.getObject(2, LocalDate.class),
                            rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                            Money.of(rs.getBigDecimal(5), Currencies.XOF),
                            Money.of(rs.getBigDecimal(6), Currencies.XOF),
                            Money.of(rs.getBigDecimal(7), Currencies.XOF),
                            Money.of(rs.getBigDecimal(8), Currencies.XOF),
                            FeeOutcome.valueOf(rs.getString(9)), rs.getObject(10, UUID.class),
                            rs.getInt(11), rs.getInt(12), rs.getInt(13)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des commissions du compte", e);
            }
            return found;
        });
    }

    protected static Money balance(Account account) {
        return database.inTransaction(
            c -> io.corebanking.ledger.store.Balances.current(c, account.id()));
    }

    protected static long entryCount(String transactionType) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM journal_entry WHERE transaction_type = ?")) {
                ps.setString(1, transactionType);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des ecritures", e);
            }
        });
    }
}
