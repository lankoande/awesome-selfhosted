package io.corebanking.ledger.store;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Socle des tests d'integration : un vrai PostgreSQL, jamais un simulacre.
 *
 * <p>Les invariants du ledger dependent du comportement transactionnel reel du moteur — contraintes
 * differees, ordre de verrouillage, niveau d'isolation, declencheurs. Un simulacre de base validerait
 * le code sans rien prouver sur ce qui se passe en production.
 */
abstract class LedgerTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 13);

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 16);

        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database,
            BUSINESS_DATE.minusMonths(6), BUSINESS_DATE.plusMonths(6));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertCurrency(c, Currencies.XAF, "Franc CFA BEAC");
            Entities.insertCurrency(c, Currencies.EUR, "Euro");
            Entities.insertCurrency(c, Currencies.USD, "Dollar des Etats-Unis");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, BUSINESS_DATE);
            Entities.openPeriod(c, ENTITY, BUSINESS_DATE.withDayOfMonth(1),
                                BUSINESS_DATE.withDayOfMonth(1).plusMonths(1).minusDays(1));
            Entities.openPeriod(c, ENTITY, BUSINESS_DATE.minusMonths(1).withDayOfMonth(1),
                                BUSINESS_DATE.withDayOfMonth(1).minusDays(1));
            return null;
        });

        postingService = new JdbcPostingService(database);
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account newCustomerAccount(String code, CurrencyRef currency) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER,
                                      NormalBalance.CREDIT, currency, true, true, 1,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    protected static Account newGlAccount(String code, CurrencyRef currency,
                                          NormalBalance normalBalance, int stripeCount) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL,
                                      normalBalance, currency, true, false, stripeCount,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }
}
