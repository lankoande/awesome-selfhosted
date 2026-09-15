package io.corebanking.interest.service;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class InterestTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static InterestAccrualService interestService;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    protected static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 13);

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);

        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database,
            BUSINESS_DATE.minusMonths(2), BUSINESS_DATE.plusMonths(2));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, BUSINESS_DATE);
            Entities.openPeriod(c, ENTITY, BUSINESS_DATE.withDayOfMonth(1),
                                BUSINESS_DATE.withDayOfMonth(1).plusMonths(1).minusDays(1));
            return null;
        });
        postingService = new JdbcPostingService(database);
        interestService = new InterestAccrualService(database, postingService);
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account customer(String code) {
        return create(new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER,
                                  NormalBalance.CREDIT, Currencies.XOF, true, true, 1,
                                  AccountStatus.ACTIVE));
    }

    protected static Account gl(String code, NormalBalance normalBalance) {
        return create(new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, normalBalance,
                                  Currencies.XOF, true, false, 1, AccountStatus.ACTIVE));
    }

    private static Account create(Account account) {
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }
}
