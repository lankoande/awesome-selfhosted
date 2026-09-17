package io.corebanking.compliance;

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
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Decor commun des tests de conformite : une entite, des clients, un journal a remplir. */
abstract class ComplianceTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static PartyService parties;

    protected static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    protected static final LocalDate J = LocalDate.of(2026, 9, 15);

    protected static Account caisse;

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, J.minusMonths(6), J.plusMonths(3));
        postingService = new JdbcPostingService(database);
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, J);
            Entities.openPeriod(c, ENTITY, J.minusMonths(6), J.plusMonths(3));
            return null;
        });
        parties = new PartyService(database, Screening.NONE);
        caisse = account("CAISSE-LCB", AccountKind.GL, NormalBalance.DEBIT);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    protected static Account account(String code, AccountKind kind, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, J.minusMonths(6));
            return null;
        });
        return account;
    }

    /** Un client verifie, et son compte. */
    protected static UUID client(String reference, RiskRating rating) {
        UUID id = parties.create(new PartyService.Draft(
            ENTITY, reference, PartyKind.NATURAL_PERSON, "Client " + reference, null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-" + reference)), ACTOR));
        parties.verifyKyc(id, rating, J.minusMonths(2), ACTOR, APPROVER);
        return id;
    }

    protected static Account compte(String code, UUID titulaire) {
        Account account = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER, J.minusMonths(6),
                                  ACTOR);
            return null;
        });
        return account;
    }

    /** Un versement d'especes au guichet, a une date donnee. */
    protected static void especes(Account client, String montant, LocalDate on, String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, on, "CASH_DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), xof(montant), on, null),
                    PostingLine.credit(client.id(), xof(montant), on, null))));
    }

    /** Un virement recu : un credit qui n'est pas des especes. */
    protected static void virementRecu(Account client, String montant, LocalDate on, String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, on, "TRANSFER", ACTOR,
            List.of(PostingLine.debit(caisse.id(), xof(montant), on, null),
                    PostingLine.credit(client.id(), xof(montant), on, null))));
    }

    protected static void dater(LocalDate date) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE legal_entity SET current_business_date = ? WHERE id = ?")) {
                ps.setObject(1, date);
                ps.setObject(2, ENTITY);
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
            }
            return null;
        });
    }

    protected static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }
}
