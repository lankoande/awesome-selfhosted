package io.corebanking.deposits;

import io.corebanking.calendar.BusinessDayConvention;
import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.OffsetUnit;
import io.corebanking.calendar.ValueDateRule;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.service.WithholdingTaxes;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import io.corebanking.product.ProductCatalog;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Chaque cas travaille sur sa propre entite juridique, avec son calendrier, ses conditions de
 * banque et ses comptes generaux : la dormance et l'expiration balaient toute une entite.
 */
abstract class DepositsTestBase {

    protected static EmbeddedPostgres postgres;
    protected static Database database;
    protected static JdbcPostingService postingService;
    protected static OperationsService operations;
    protected static AccountLifecycle lifecycle;
    protected static PartyService parties;

    protected static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    protected static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    /** Mardi 15 septembre 2026. */
    protected static final LocalDate J = LocalDate.of(2026, 9, 15);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 8);

        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, J.minusMonths(15), J.plusMonths(3));

        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            return null;
        });
        postingService = new JdbcPostingService(database);
        operations = new OperationsService(database, postingService);
        lifecycle = new AccountLifecycle(database, postingService);
        parties = new PartyService(database, Screening.NONE);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    // ------------------------------------------------------------------ decor

    /** Comptes generaux d'une entite de test. */
    protected record Decor(UUID entityId, Account caisse, Account attente, Account produitsFrais,
                           Account taxe, Account charges, Account courus, Account irc,
                           Account agiosCourus, Account produitsAgios, Account taf) {}

    protected static Decor decor(String code) {
        return decor(code, true);
    }

    /** Une entite ; avec ou sans conditions de date de valeur. */
    protected static Decor decor(String code, boolean conditionsDeBanque) {
        UUID entityId = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, entityId, code, "Banque " + code, "CI", Currencies.XOF, J);
            Entities.openPeriod(c, entityId, J.minusMonths(15), J.plusMonths(3));
            UUID calendarId = Calendars.createCalendar(c, "CAL-" + code, "Calendrier " + code,
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), J.minusYears(2), J.plusYears(1));
            Calendars.attachToEntity(c, entityId, calendarId);
            if (conditionsDeBanque) {
                for (String type : List.of(OperationSchemas.CASH_DEPOSIT, OperationSchemas.TRANSFER)) {
                    regle(c, entityId, type, null, Direction.CREDIT, 0, OffsetUnit.CALENDAR_DAYS);
                }
                for (String type : List.of(OperationSchemas.CASH_WITHDRAWAL,
                                           OperationSchemas.TRANSFER)) {
                    regle(c, entityId, type, null, Direction.DEBIT, 0, OffsetUnit.CALENDAR_DAYS);
                }
                // Au guichet, un versement d'especes prend valeur le jour ouvre suivant.
                regle(c, entityId, OperationSchemas.CASH_DEPOSIT, "GUICHET", Direction.CREDIT, 1,
                      OffsetUnit.BUSINESS_DAYS);
            }
            return null;
        });
        Decor decor = new Decor(entityId,
            account(entityId, code + "-CAISSE", AccountKind.INTERNAL, NormalBalance.DEBIT),
            account(entityId, code + "-CLOS-A-PAYER", AccountKind.INTERNAL, NormalBalance.CREDIT),
            account(entityId, code + "-FRAIS", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-TAXE", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-CHARGES", AccountKind.GL, NormalBalance.DEBIT),
            account(entityId, code + "-COURUS", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-IRC", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-AGIOS-COURUS", AccountKind.GL, NormalBalance.DEBIT),
            account(entityId, code + "-PRODUITS-AGIOS", AccountKind.GL, NormalBalance.CREDIT),
            account(entityId, code + "-TAF", AccountKind.GL, NormalBalance.CREDIT));
        database.inTransaction(c -> WithholdingTaxes.declare(
            c, entityId, "IRC", new BigDecimal("15"), decor.irc().id(), LocalDate.of(2020, 1, 1),
            null, ACTOR));
        return decor;
    }

    private static void regle(java.sql.Connection c, UUID entityId, String type, String channel,
                              Direction direction, int offset, OffsetUnit unit) {
        Calendars.addRule(c, entityId, new ValueDateRule(type, channel, direction, offset, unit,
                                                         BusinessDayConvention.UNADJUSTED,
                                                         LocalDate.of(2020, 1, 1), null),
                          ACTOR, APPROVER);
    }

    protected static Account account(UUID entityId, String code, AccountKind kind,
                                     NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), entityId, code, kind, normalBalance,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, J.minusMonths(14));
            return null;
        });
        return account;
    }

    /** Epargne a 6 %, capitalisee chaque trimestre, retenue IRC ; frais selon surcharges. */
    protected static void produit(Decor decor, String code, String famille,
                                  Map<String, String> surcharges) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "6");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_WITHHOLDING, "IRC");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, decor.charges().id().toString());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, decor.courus().id().toString());
        parametres.putAll(surcharges);
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                decor.entityId(), code, famille, "Produit " + code, "XOF", J.minusMonths(14), null,
                parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    /** Frais de retrait 500, de virement 200, taxes a 18 %. */
    protected static Map<String, String> frais(Decor decor) {
        return Map.of(DepositCatalog.P_WITHDRAWAL_FEE, "500",
                      DepositCatalog.P_TRANSFER_FEE, "200",
                      DepositCatalog.P_FEE_INCOME, decor.produitsFrais().id().toString(),
                      DepositCatalog.P_TAX_RATE, "18",
                      DepositCatalog.P_TAX_ACCOUNT, decor.taxe().id().toString());
    }

    /** Compte courant a taux nul, decouvert a 12 % (18 % au-dela), taxe 10 %. */
    protected static Map<String, String> decouvert(Decor decor) {
        return Map.ofEntries(
            Map.entry(ProductCatalog.P_RATE, "0"),
            Map.entry(ProductCatalog.P_CAPITALISATION, "MONTHLY"),
            Map.entry(ProductCatalog.P_OD_RATE, "12"),
            Map.entry(ProductCatalog.P_OD_EXCESS_RATE, "18"),
            Map.entry(ProductCatalog.P_OD_SETTLEMENT, "MONTHLY"),
            Map.entry(ProductCatalog.P_OD_TAX_RATE, "10"),
            Map.entry(ProductCatalog.P_OD_TAX_ACCOUNT, decor.taf().id().toString()),
            Map.entry(ProductCatalog.P_OD_DEBIT_ACCOUNT, decor.agiosCourus().id().toString()),
            Map.entry(ProductCatalog.P_OD_CREDIT_ACCOUNT, decor.produitsAgios().id().toString()));
    }

    // ------------------------------------------------------------------ tiers et comptes

    /** Un client verifie de l'entite. */
    protected static UUID client(UUID entityId, String reference) {
        UUID id = clientNonVerifie(entityId, reference);
        parties.verifyKyc(id, RiskRating.MEDIUM, J.minusMonths(1), ACTOR, APPROVER);
        return id;
    }

    protected static UUID clientNonVerifie(UUID entityId, String reference) {
        return parties.create(new PartyService.Draft(
            entityId, reference, PartyKind.NATURAL_PERSON, "Client " + reference, null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-" + reference)), ACTOR));
    }

    protected static UUID ouvrir(Decor decor, String code, String produit, UUID partyId) {
        return ouvrir(decor, code, produit, partyId, siege(decor));
    }

    protected static UUID ouvrir(Decor decor, String code, String produit, UUID partyId,
                                 UUID agence) {
        return lifecycle.open(new AccountLifecycle.Opening(decor.entityId(), code, partyId, produit,
                                                           Currencies.XOF, agence, ACTOR, APPROVER));
    }

    protected static UUID siege(Decor decor) {
        return database.inTransaction(
            c -> io.corebanking.ledger.store.Branches.headOffice(c, decor.entityId()));
    }

    protected static OperationsService.Receipt verser(Decor decor, UUID accountId, String montant,
                                                      String key) {
        return verser(decor, accountId, montant, key, null);
    }

    protected static OperationsService.Receipt verser(Decor decor, UUID accountId, String montant,
                                                      String key, String canal) {
        return operations.deposit(new OperationsService.Deposit(
            IdempotencyKey.of(key), decor.entityId(), accountId, decor.caisse().id(), xof(montant),
            canal, "versement", ACTOR));
    }

    protected static OperationsService.Receipt retirer(Decor decor, UUID accountId, String montant,
                                                       String key) {
        return operations.withdraw(new OperationsService.Withdrawal(
            IdempotencyKey.of(key), decor.entityId(), accountId, decor.caisse().id(), xof(montant),
            null, "retrait", ACTOR));
    }

    protected static OperationsService.Receipt virer(Decor decor, UUID from, UUID to,
                                                     String montant, String key) {
        return operations.transfer(new OperationsService.Transfer(
            IdempotencyKey.of(key), decor.entityId(), from, to, xof(montant), null, "virement",
            ACTOR));
    }

    // ------------------------------------------------------------------ lectures

    protected static Money solde(UUID accountId) {
        return database.inTransaction(c -> Balances.current(c, accountId));
    }

    protected static Money solde(Account account) {
        return solde(account.id());
    }

    protected static Money disponible(UUID accountId, LocalDate at) {
        return database.inTransaction(c -> Balances.available(c, accountId, at));
    }

    protected static Account compte(UUID accountId) {
        return database.inTransaction(c -> Accounts.loadAll(c, Set.of(accountId)).get(accountId));
    }

    protected static List<String> historique(UUID accountId) {
        return database.inTransaction(c -> AccountLifecycle.history(c, accountId).stream()
            .map(AccountLifecycle.Event::kind).toList());
    }

    /** Avance la date comptable de l'entite sans arrete : ce que le TFJ ferait chaque nuit. */
    protected static void dater(Decor decor, LocalDate date) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE legal_entity SET current_business_date = ? WHERE id = ?")) {
                ps.setObject(1, date);
                ps.setObject(2, decor.entityId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Date comptable", e);
            }
            return null;
        });
    }

    protected static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }
}
