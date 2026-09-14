package io.corebanking.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalendarIT {

    private static EmbeddedPostgres postgres;
    private static Database database;

    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID REDACTEUR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID VALIDEUR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final LocalDate DEBUT = LocalDate.of(2026, 1, 1);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database);
        applyScript("/db/V7__calendar.sql");
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque", "CI", Currencies.XOF, DEBUT);
            return null;
        });
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    private static void applyScript(String resource) {
        String sql;
        try (InputStream in = CalendarIT.class.getResourceAsStream(resource)) {
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

    private ValueDateRule rule(String type, String channel, Direction sens, int decalage,
                               LocalDate from, LocalDate to) {
        return new ValueDateRule(type, channel, sens, decalage, OffsetUnit.BUSINESS_DAYS,
                                 BusinessDayConvention.FOLLOWING, from, to);
    }

    @Test
    @DisplayName("le calendrier et ses conditions se relisent tels qu'ils ont ete saisis")
    void the_calendar_and_its_rules_round_trip() {
        database.inTransaction(c -> {
            UUID id = Calendars.createCalendar(c, "CI-PRINCIPAL", "Cote d'Ivoire",
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), DEBUT, DEBUT.plusYears(2));
            Calendars.addHoliday(c, id, LocalDate.of(2026, 8, 7), "Fete nationale");
            Calendars.attachToEntity(c, ENTITY, id);
            Calendars.addRule(c, ENTITY,
                rule("DEPOSIT", null, Direction.CREDIT, 1, DEBUT, null), REDACTEUR, VALIDEUR);
            Calendars.addRule(c, ENTITY,
                rule("DEPOSIT", "BRANCH", Direction.CREDIT, 0, DEBUT, null), REDACTEUR, VALIDEUR);
            return null;
        });

        ValueDatePolicy policy = Calendars.load(database, ENTITY);

        // Le 7 aout 2026 est un vendredi ferie : le jour ouvre suivant est le lundi 10.
        assertThat(policy.calendar().isBusinessDay(LocalDate.of(2026, 8, 7))).isFalse();
        assertThat(policy.calendar().nextBusinessDay(LocalDate.of(2026, 8, 6)))
            .isEqualTo(LocalDate.of(2026, 8, 10));

        assertThat(policy.valueDateFor("DEPOSIT", "BRANCH", Direction.CREDIT,
                                       LocalDate.of(2026, 8, 6)))
            .isEqualTo(LocalDate.of(2026, 8, 6));
        // Hors guichet, un jour ouvre plus tard : le ferie et le week-end se traversent d'un coup.
        assertThat(policy.valueDateFor("DEPOSIT", "MOBILE", Direction.CREDIT,
                                       LocalDate.of(2026, 8, 6)))
            .isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    @DisplayName("deux conditions de meme portee aux periodes qui se chevauchent sont refusees")
    void overlapping_rules_of_the_same_scope_are_refused() {
        assertThatThrownBy(() -> database.inTransaction(c -> {
            Calendars.addRule(c, ENTITY,
                rule("WITHDRAWAL", null, Direction.DEBIT, 0, DEBUT, DEBUT.plusMonths(6)),
                REDACTEUR, VALIDEUR);
            Calendars.addRule(c, ENTITY,
                rule("WITHDRAWAL", null, Direction.DEBIT, -1, DEBUT.plusMonths(3), null),
                REDACTEUR, VALIDEUR);
            return null;
        })).hasStackTraceContaining("ex_rule_no_overlap");
    }

    @Test
    @DisplayName("le redacteur d'une condition de banque ne peut pas la valider lui-meme")
    void maker_cannot_approve_their_own_banking_condition() {
        assertThatThrownBy(() -> database.inTransaction(c -> Calendars.addRule(c, ENTITY,
            rule("TRANSFER_OUT", null, Direction.DEBIT, 0, DEBUT, null), REDACTEUR, REDACTEUR)))
            .hasStackTraceContaining("ck_rule_approval");
    }

    @Test
    @DisplayName("une entite sans calendrier refuse de determiner une date de valeur")
    void an_entity_without_calendar_refuses_to_answer() {
        UUID orpheline = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, orpheline, "BANK-SN", "Filiale", "SN", Currencies.XOF,
                                       DEBUT);
            return null;
        });

        assertThatThrownBy(() -> Calendars.load(database, orpheline))
            .isInstanceOf(LedgerStoreException.class)
            .hasMessageContaining("aucune date de valeur ne peut etre determinee");
    }
}
