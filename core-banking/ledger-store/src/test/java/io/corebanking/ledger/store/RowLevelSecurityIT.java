package io.corebanking.ledger.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le cloisonnement par entite applique par la base elle-meme, a un role qui ne possede rien.
 *
 * <p>Les autres tests tournent avec le proprietaire des tables, que les politiques ne concernent
 * pas. Celui-ci se connecte comme l'application le fait en production : avec le role applicatif
 * de {@code ops/roles.sql}, cree ici avec les memes droits.
 */
class RowLevelSecurityIT extends LedgerTestBase {

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    private static Database app;
    private static JdbcPostingService appPosting;
    private static Account clientA;
    private static Account caisseA;
    private static Account clientB;
    private static Account caisseB;

    @BeforeAll
    static void applicationRole() {
        database.inTransaction(c -> {
            for (String statement : List.of(
                "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'corebanking_app')"
                + " THEN CREATE ROLE corebanking_app LOGIN PASSWORD 'app'; END IF; END $$",
                "GRANT USAGE ON SCHEMA public TO corebanking_app",
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public"
                + " TO corebanking_app",
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO corebanking_app",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE"
                + " ON TABLES TO corebanking_app",
                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES"
                + " TO corebanking_app")) {
                execute(c, statement);
            }
            Entities.insertLegalEntity(c, OTHER, "BANK-SN", "Autre banque", "SN", XOF,
                                       BUSINESS_DATE);
            Entities.openPeriod(c, OTHER, BUSINESS_DATE.withDayOfMonth(1),
                                BUSINESS_DATE.withDayOfMonth(1).plusMonths(1).minusDays(1));
            return null;
        });
        clientA = newCustomerAccount("RLS-CLI-A", XOF);
        caisseA = newGlAccount("RLS-CAISSE-A", XOF, NormalBalance.DEBIT, 1);
        clientB = account(OTHER, "RLS-CLI-B", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        caisseB = account(OTHER, "RLS-CAISSE-B", AccountKind.GL, NormalBalance.DEBIT);

        app = new Database("jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres",
                           "corebanking_app", "app", 4);
        appPosting = new JdbcPostingService(app);
    }

    @AfterAll
    static void closeApplicationRole() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("sans entite posee, le role applicatif ne voit aucune ligne : le defaut est l'absence d'acces")
    void nothingIsVisibleWithoutAnEntity() {
        assertThat(count(app, null, "legal_entity")).isZero();
        assertThat(count(app, null, "branch")).isZero();
        assertThat(count(app, null, "account")).isZero();
        assertThat(count(app, null, "accounting_period")).isZero();
        // Le proprietaire, lui, voit tout : les politiques ne concernent pas celui qui possede.
        assertThat(count(database, null, "legal_entity")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("l'entite posee est la seule visible, tables meres et tables filles")
    void onlyTheCurrentEntityIsVisible() {
        Set<UUID> both = Set.of(clientA.id(), clientB.id());
        Map<UUID, Account> underA = app.inEntity(ENTITY, c -> Accounts.loadAll(c, both));
        Map<UUID, Account> underB = app.inEntity(OTHER, c -> Accounts.loadAll(c, both));
        assertThat(underA).containsOnlyKeys(clientA.id());
        assertThat(underB).containsOnlyKeys(clientB.id());
        assertThat(count(app, ENTITY, "legal_entity")).isEqualTo(1);
        assertThat(countWhere(app, ENTITY, "branch", "legal_entity_id", OTHER)).isZero();
        assertThat(countWhere(app, OTHER, "branch", "legal_entity_id", OTHER)).isEqualTo(1);
        // Une transaction independante ouverte sous la portee — la piste d'audit — l'herite.
        long inherited = app.inEntity(ENTITY,
                                      c -> app.inNewTransaction(c2 -> count(c2, "legal_entity")));
        assertThat(inherited).isEqualTo(1);
    }

    @Test
    @DisplayName("ecrire hors de l'entite posee est refuse par la base, quelle que soit la requete")
    void writingOutsideTheEntityIsRefusedByTheDatabase() {
        UUID third = UUID.randomUUID();
        assertThatThrownBy(() -> app.inEntity(ENTITY, c -> {
            Entities.insertLegalEntity(c, third, "BANK-X", "Tierce", "ML", XOF, BUSINESS_DATE);
            return null;
        })).hasStackTraceContaining("row-level security policy");
        assertThat(countWhere(database, null, "legal_entity", "id", third)).isZero();

        // Un compte de l'autre entite ne se cree pas non plus : son entite n'existe pas ici.
        Account intrus = new Account(UUID.randomUUID(), OTHER, "RLS-INTRUS", AccountKind.CUSTOMER,
                                     NormalBalance.CREDIT, XOF, true, true, 1, AccountStatus.ACTIVE);
        assertThatThrownBy(() -> app.inEntity(ENTITY, c -> {
            Accounts.create(c, intrus);
            return null;
        })).isInstanceOf(LedgerStoreException.class).hasMessageContaining("Entite inconnue");
    }

    @Test
    @DisplayName("une imputation sur les comptes d'une autre entite echoue : ils n'existent pas pour elle")
    void postingAcrossEntitiesIsRefused() {
        try (Database.EntityScope scope = Database.enterEntity(ENTITY)) {
            assertThatThrownBy(() -> appPosting.post(command(ENTITY, clientB, caisseB, "1000", "rls-x")))
                .isInstanceOf(InvalidPostingException.class)
                .hasMessageContaining("Compte inconnu");
        }

        // Sous sa propre entite, la meme imputation passe — et n'est visible que d'elle.
        PostingResult posted;
        try (Database.EntityScope scope = Database.enterEntity(ENTITY)) {
            posted = appPosting.post(command(ENTITY, clientA, caisseA, "1000", "rls-ok"));
        }
        assertThat(posted.replayed()).isFalse();
        assertThat(posted.balancesAfter().get(clientA.id())).isEqualTo(Money.of("1000", XOF));
        assertThat(countWhere(app, ENTITY, "journal_entry", "id", posted.entryId())).isEqualTo(1);
        assertThat(countWhere(app, OTHER, "journal_entry", "id", posted.entryId())).isZero();
        assertThat(countWhere(app, null, "journal_entry", "id", posted.entryId())).isZero();
        assertThat(countWhere(app, ENTITY, "journal_line", "entry_id", posted.entryId()))
            .isEqualTo(2);
    }

    @Test
    @DisplayName("le role applicatif cree les partitions du journal sans posseder les tables")
    void applicationRoleCreatesPartitions() {
        LocalDate far = BUSINESS_DATE.plusYears(3).withDayOfMonth(1);
        assertThat(partitionExists(far)).isFalse();
        SchemaMigrator.ensurePartitions(app, far, far);
        assertThat(partitionExists(far)).isTrue();
    }

    @Test
    @DisplayName("une transaction ne change pas d'entite : la portee se pose avant de l'ouvrir")
    void anOpenTransactionKeepsItsEntity() {
        assertThatThrownBy(() -> app.inEntity(ENTITY, c -> app.inEntity(OTHER, c2 -> null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Changement d'entite");
        assertThatThrownBy(() -> app.inTransaction(c -> {
            try (Database.EntityScope scope = Database.enterEntity(ENTITY)) {
                return null;
            }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("sans entite");
        // La meme entite se confirme sans rien changer.
        long nested = app.inEntity(ENTITY, c -> app.inEntity(ENTITY, c2 -> count(c2, "legal_entity")));
        assertThat(nested).isEqualTo(1);
        // Et la portee ne fuit pas hors de son bloc, ni vers la transaction suivante.
        assertThat(Database.currentEntity()).isEmpty();
        assertThat(count(app, null, "legal_entity")).isZero();
    }

    // ------------------------------------------------------------------ outillage

    private static Account account(UUID entity, String code, AccountKind kind, NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), entity, code, kind, normal, XOF, true,
                                      kind == AccountKind.CUSTOMER, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account);
            return null;
        });
        return account;
    }

    private static PostingCommand command(UUID entity, Account client, Account caisse, String amount,
                                          String key) {
        return PostingCommand.online(
            IdempotencyKey.of(key), entity, BUSINESS_DATE, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), BUSINESS_DATE, "Versement"),
                    PostingLine.credit(client.id(), Money.of(amount, XOF), BUSINESS_DATE, "Versement")));
    }

    /** Compte les lignes visibles par {@code db}, sous l'entite donnee ou sans entite posee. */
    private static long count(Database db, UUID entity, String table) {
        return within(db, entity, c -> count(c, table));
    }

    private static long countWhere(Database db, UUID entity, String table, String column,
                                   UUID value) {
        return within(db, entity, c -> countWhere(c, table, column, value));
    }

    private static <T> T within(Database db, UUID entity, Function<Connection, T> work) {
        return entity == null ? db.inTransaction(work) : db.inEntity(entity, work);
    }

    private static long count(Connection c, String table) {
        return scalar(c, "SELECT count(*) FROM " + table, null);
    }

    private static long countWhere(Connection c, String table, String column, UUID value) {
        return scalar(c, "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", value);
    }

    private static long scalar(Connection c, String sql, UUID parameter) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (parameter != null) {
                ps.setObject(1, parameter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException(sql, e);
        }
    }

    private static boolean partitionExists(LocalDate date) {
        return database.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ledger_partition_exists(?)")) {
                ps.setObject(1, date);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("partition", e);
            }
        });
    }

    private static void execute(Connection c, String statement) {
        try (Statement s = c.createStatement()) {
            s.execute(statement);
        } catch (SQLException e) {
            throw new LedgerStoreException(statement, e);
        }
    }

}
