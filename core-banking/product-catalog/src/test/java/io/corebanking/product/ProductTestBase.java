package io.corebanking.product;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

abstract class ProductTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID REDACTEUR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    protected static final UUID VALIDEUR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    protected static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 13);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);
        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, BUSINESS_DATE);
            return null;
        });
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account gl(String code) {
        return gl(code, NormalBalance.CREDIT);
    }

    protected static Account gl(String code, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL,
                                      normalBalance, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    protected static Account customer(String code) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER,
                                      NormalBalance.CREDIT, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }
}
