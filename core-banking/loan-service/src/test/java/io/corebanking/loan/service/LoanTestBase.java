package io.corebanking.loan.service;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
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

/**
 * Chaque cas travaille sur sa propre entite juridique. L'exigibilite balaie tous les credits d'une
 * entite : partager l'entite ferait dependre un test de l'ordre d'execution des autres.
 */
abstract class LoanTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static LoanService loanService;

    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    protected static final LocalDate DEBLOCAGE = LocalDate.of(2026, 9, 15);
    protected static final LocalDate PREMIERE_ECHEANCE = LocalDate.of(2026, 10, 15);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);

        SchemaMigrator.migrate(database);
        applyScript("/db/V3__product.sql");
        applyScript("/db/V5__accounting_schema.sql");
        applyScript("/db/V9__loans.sql");
        SchemaMigrator.ensurePartitions(database, DEBLOCAGE.minusMonths(2),
                                        DEBLOCAGE.plusMonths(36));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            return null;
        });
        postingService = new JdbcPostingService(database);
        loanService = new LoanService(database, postingService);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static void applyScript(String resource) {
        String sql;
        try (InputStream in = LoanTestBase.class.getResourceAsStream(resource)) {
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

    protected static UUID entity(String code) {
        UUID id = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, id, code, "Banque " + code, "CI", Currencies.XOF,
                                       DEBLOCAGE);
            Entities.openPeriod(c, id, DEBLOCAGE.minusMonths(2), DEBLOCAGE.plusMonths(36));
            return null;
        });
        return id;
    }

    protected static Account account(UUID entityId, String code, AccountKind kind,
                                     NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), entityId, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, DEBLOCAGE.minusMonths(1));
            return null;
        });
        return account;
    }

    /** Comptes generaux et comptes clients d'un dossier de credit. */
    protected record Decor(UUID entityId, Account pret, Account courant, Account creances,
                           Account produitsInterets, Account taxe, Account caisse) {}

    protected static Decor decor(String code) {
        UUID entityId = entity(code);
        return new Decor(entityId,
            account(entityId, code + "-PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT),
            account(entityId, code + "-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT),
            account(entityId, code + "-CREANCES", AccountKind.GL, NormalBalance.DEBIT),
            account(entityId, code + "-PRODUITS", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-TAXE", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-CAISSE", AccountKind.GL, NormalBalance.DEBIT));
    }

    protected static void product(Decor decor, String code, Map<String, String> surcharges) {
        java.util.Map<String, String> parametres = new java.util.LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, decor.creances().id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, decor.produitsInterets().id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, decor.taxe().id().toString());
        parametres.putAll(surcharges);

        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                decor.entityId(), code, "TERM_LOAN", "Credit amortissable", "XOF",
                DEBLOCAGE.minusMonths(1), null, parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    protected static UUID contract(Decor decor, String reference, String productCode,
                                   String capital) {
        return database.inTransaction(c -> LoanStore.createContract(c, new LoanStore.ContractDraft(
            decor.entityId(), reference, productCode, Currencies.XOF, decor.pret().id(),
            decor.courant().id(), Money.of(capital, Currencies.XOF), DEBLOCAGE, ACTOR)));
    }

    protected static void alimenter(Decor decor, String montant, LocalDate valueDate, String key) {
        postingService.post(io.corebanking.ledger.domain.posting.PostingCommand.online(
            io.corebanking.kernel.id.IdempotencyKey.of(key), decor.entityId(), valueDate, "DEPOSIT",
            ACTOR,
            List.of(io.corebanking.ledger.domain.posting.PostingLine.debit(
                        decor.caisse().id(), Money.of(montant, Currencies.XOF), valueDate, null),
                    io.corebanking.ledger.domain.posting.PostingLine.credit(
                        decor.courant().id(), Money.of(montant, Currencies.XOF), valueDate, null))));
    }

    protected static Money solde(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }

    /** Creance ouverte, telle que les assertions la lisent. */
    protected record Creance(String category, LocalDate dueDate, Money original, Money outstanding,
                             boolean cancelled) {}

    protected static List<Creance> creances(UUID contractId) {
        return database.inTransaction(c -> {
            List<Creance> found = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT category, due_date, original_amount, outstanding, cancelled"
                + " FROM loan_receivable WHERE contract_id = ?"
                + " ORDER BY due_date, instalment_number, category")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(new Creance(rs.getString(1), rs.getObject(2, LocalDate.class),
                                              Money.of(rs.getBigDecimal(3), Currencies.XOF),
                                              Money.of(rs.getBigDecimal(4), Currencies.XOF),
                                              rs.getBoolean(5)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des creances", e);
            }
            return found;
        });
    }

    protected static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }
}
