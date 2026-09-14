package io.corebanking.benchmark;

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Socle des bancs de mesure.
 *
 * <h2>Ce que ces mesures valent, et ce qu'elles ne valent pas</h2>
 *
 * <p>Elles tournent sur un PostgreSQL embarque, dans un conteneur partage, sans reglage de moteur.
 * Les valeurs absolues ne sont donc <b>pas</b> des chiffres de production : une base dediee, avec
 * ses disques et sa memoire, fait plusieurs fois mieux.
 *
 * <p>Ce qu'elles mesurent fidelement, en revanche, c'est le <b>cout unitaire</b> — nombre
 * d'allers-retours par operation, par compte, par journee — et son evolution. Un traitement qui
 * coute dix requetes par compte en coutera dix sur une base dix fois plus rapide ; seul le facteur
 * change, jamais l'ordre de grandeur du rapport. C'est ce cout unitaire qui decide si un TFJ tient
 * dans sa fenetre, et c'est lui que l'optimisation doit faire baisser.
 */
abstract class BenchmarkBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static BatchInterestAccrualService interestService;
    protected static BusinessCalendar calendar;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    protected static final LocalDate DAY = LocalDate.of(2026, 9, 14);

    protected static int sizing(String property, int fallback) {
        return Integer.getInteger("bench." + property, fallback);
    }

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder()
            .setServerConfig("fsync", "off")               // banc de mesure, pas de production
            .setServerConfig("synchronous_commit", "off")
            .setServerConfig("max_connections", "200")
            .start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 32);

        SchemaMigrator.migrate(database);
        applyScript("/db/V3__product.sql");
        applyScript("/db/V2__interest.sql");
        applyScript("/db/V6__tfj.sql");
        applyScript("/db/V5__accounting_schema.sql");
        applyScript("/db/V7__calendar.sql");
        applyScript("/db/V8__fees.sql");
        applyScript("/db/V9__loans.sql");
        applyScript("/db/V10__loan_late_charges.sql");
        applyScript("/db/V11__loan_risk.sql");
        applyScript("/db/V12__loan_teg.sql");
        applyScript("/db/V13__loan_prepayment.sql");
        applyScript("/db/V14__collateral.sql");
        SchemaMigrator.ensurePartitions(database, DAY.minusMonths(1), DAY.plusMonths(2));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque", "CI", Currencies.XOF, DAY);
            Entities.openPeriod(c, ENTITY, DAY.withDayOfMonth(1),
                                DAY.withDayOfMonth(1).plusMonths(2).minusDays(1));
            return null;
        });
        UUID calendarId = database.inTransaction(c -> {
            UUID id = Calendars.createCalendar(c, "CI", "Cote d'Ivoire",
                java.util.Set.of(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY),
                DAY.minusYears(1), DAY.plusYears(2));
            Calendars.attachToEntity(c, ENTITY, id);
            return id;
        });
        calendar = Calendars.load(database, ENTITY).calendar();

        postingService = new JdbcPostingService(database);
        interestService = new BatchInterestAccrualService(database, postingService);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static void applyScript(String resource) {
        String sql;
        try (InputStream in = BenchmarkBase.class.getResourceAsStream(resource)) {
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

    protected static Account gl(String code, NormalBalance sens, int stripes) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, sens,
                                      Currencies.XOF, true, false, stripes, AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    /** Cree et alimente N comptes clients. */
    protected static List<Account> seedCustomers(int count, Account cash, String amount) {
        List<Account> accounts = new ArrayList<>(count);
        database.inTransaction(c -> {
            for (int i = 0; i < count; i++) {
                Account account = new Account(UUID.randomUUID(), ENTITY, "CLI-" + i,
                                              AccountKind.CUSTOMER, NormalBalance.CREDIT,
                                              Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
                Accounts.create(c, account);
                accounts.add(account);
            }
            return null;
        });
        for (int i = 0; i < accounts.size(); i++) {
            postingService.post(PostingCommand.online(
                IdempotencyKey.of("seed-" + i), ENTITY, DAY, "DEPOSIT", ACTOR,
                List.of(PostingLine.debit(cash.id(), Money.of(amount, Currencies.XOF), DAY, null),
                        PostingLine.credit(accounts.get(i).id(), Money.of(amount, Currencies.XOF),
                                           DAY, null))));
        }
        return accounts;
    }

    /** Rattache tous les comptes a un produit remunere. */
    protected static void attachProduct(List<Account> accounts, Account charges, Account accrued) {
        attachProduct(accounts, charges, accrued, Map.of());
    }

    /**
     * Rattache le portefeuille a un produit remunere, eventuellement porteur de commissions.
     *
     * <p>Les surcharges servent a mesurer le cas defavorable : une commission ancree sur une date
     * fixe du produit rend tous les comptes exigibles le meme jour. C'est la situation reelle
     * d'une banque qui facture la tenue de compte le premier du mois, et la seule qui dimensionne
     * la fenetre de traitement.
     */
    protected static void attachProduct(List<Account> accounts, Account charges, Account accrued,
                                        Map<String, String> surcharges) {
        java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>(
            Map.of(ProductCatalog.P_RATE, "6",
                   ProductCatalog.P_DAY_COUNT, "ACT_365",
                   ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name(),
                   ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString(),
                   ProductCatalog.P_CREDIT_ACCOUNT, accrued.id().toString()));
        parameters.putAll(surcharges);
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "EP-BENCH", "SAVINGS_ACCOUNT", "Epargne", "XOF", DAY.minusMonths(1), null,
                parameters, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            for (Account account : accounts) {
                ProductCatalog.assignProduct(c, account.id(), "EP-BENCH", DAY.minusMonths(1), null);
            }
            return null;
        });
    }

    protected static void analyze() {
        database.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("ANALYZE");
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("ANALYZE", e);
            }
        });
    }

    protected static void line(String text) {
        System.out.println(text);
    }
}
