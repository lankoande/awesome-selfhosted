package io.corebanking.security.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.security.AccessDeniedException;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.AuthorizationService;
import io.corebanking.security.Caller;
import io.corebanking.security.Operation;
import io.corebanking.security.Roles;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizationAuditIT {

    private static EmbeddedPostgres postgres;
    private static Database database;
    private static JdbcAuthorizationAudit audit;
    private static AuthorizationService service;

    private static final UUID ENTITE = UUID.randomUUID();
    private static final UUID AGENCE = UUID.randomUUID();

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database);
        applyScript("/db/V4__security.sql");
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITE, "BANK-CI", "Banque", "CI", Currencies.XOF,
                                       LocalDate.of(2026, 9, 13));
            return null;
        });
        audit = new JdbcAuthorizationAudit(database);
        service = new AuthorizationService(audit);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    private static void applyScript(String resource) {
        String sql;
        try (InputStream in = AuthorizationAuditIT.class.getResourceAsStream(resource)) {
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

    private Caller caller(String subject, String... roles) {
        return new Caller(subject, subject + "@bank", Set.of(roles), ENTITE, AGENCE);
    }

    @Test
    @DisplayName("un refus est enregistre, avec son motif")
    void a_denial_is_recorded_with_its_reason() {
        Caller guichetier = caller("t-100", Roles.TELLER);

        assertThatThrownBy(() -> service.require(guichetier, Operation.TFJ_CANCEL,
            AccessTarget.inEntity(ENTITE))).isInstanceOf(AccessDeniedException.class);

        assertThat(audit.deniedCountFor("t-100")).isEqualTo(1);
    }

    @Test
    @DisplayName("une consultation reussie est tracee : c'est la seule facon de voir un acces abusif")
    void a_successful_read_is_traced() {
        Caller guichetier = caller("t-101", Roles.TELLER);

        for (int i = 0; i < 5; i++) {
            service.require(guichetier, Operation.ACCOUNT_BALANCE_READ,
                AccessTarget.inBranch(ENTITE, AGENCE));
        }

        assertThat(audit.readCountFor("t-101", Operation.ACCOUNT_BALANCE_READ)).isEqualTo(5);
        assertThat(audit.deniedCountFor("t-101")).isZero();
    }

    @Test
    @DisplayName("une operation autorisee non sensible en lecture n'encombre pas la piste d'audit")
    void a_non_sensitive_success_is_not_traced() {
        Caller guichetier = caller("t-102", Roles.TELLER);

        service.require(guichetier, Operation.CASH_OPERATION,
            AccessTarget.inBranch(ENTITE, AGENCE).withAmount(Money.of("100000", XOF)));

        assertThat(audit.readCountFor("t-102", Operation.CASH_OPERATION)).isZero();
    }

    @Test
    @DisplayName("le refus conserve le montant refuse, pas seulement le fait du refus")
    void the_refused_amount_is_kept() {
        Caller guichetier = caller("t-103", Roles.TELLER);

        assertThatThrownBy(() -> service.require(guichetier, Operation.CASH_OPERATION,
            AccessTarget.inBranch(ENTITE, AGENCE).withAmount(Money.of("50000000", XOF))))
            .isInstanceOf(AccessDeniedException.class);

        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT amount, currency, reason FROM authorization_audit"
                + " WHERE subject_id = 't-103'")) {
                try (var rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("50000000");
                    assertThat(rs.getString(2)).isEqualTo("XOF");
                    assertThat(rs.getString(3)).contains("plafond");
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("la piste d'audit des habilitations est immuable")
    void the_audit_trail_is_immutable() {
        Caller guichetier = caller("t-104", Roles.TELLER);
        service.require(guichetier, Operation.ACCOUNT_BALANCE_READ,
            AccessTarget.inBranch(ENTITE, AGENCE));

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM authorization_audit WHERE subject_id = 't-104'");
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        })).hasStackTraceContaining("immuable");
    }
}
