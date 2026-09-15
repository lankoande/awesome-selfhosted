package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.SchemaMigrator;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Franchissement de mois et d'annee par le seul traitement de fin de journee.
 *
 * <p>Jusqu'ici, ni les partitions du journal ni les periodes comptables n'etaient creees hors des
 * tests : la premiere ecriture d'un mois non prepare a la main arretait la banque. Ce cas reproduit
 * l'exploitation telle qu'elle sera : decembre est prepare, janvier ne l'est pas, et le TFJ du
 * 31 decembre doit suffire.
 */
class TfjPeriodIT extends TfjTestBase {

    private static final LocalDate SAINT_SYLVESTRE = LocalDate.of(2026, 12, 31);   // jeudi

    @Test
    @DisplayName("le TFJ du 31 decembre ouvre janvier — partition et periode — et celui du 1er janvier remunere")
    void the_year_end_run_prepares_january_on_its_own() {
        // Etat d'exploitation : decembre existe, janvier n'existe pas.
        database.inTransaction(c -> {
            TfjEngine.setBusinessDate(c, ENTITY, SAINT_SYLVESTRE);
            Entities.openPeriod(c, ENTITY, LocalDate.of(2026, 12, 1), SAINT_SYLVESTRE);
            SchemaMigrator.ensurePartitions(c, LocalDate.of(2026, 12, 1), SAINT_SYLVESTRE);
            return null;
        });
        assertThat(partitionExists(LocalDate.of(2027, 1, 1))).isFalse();
        assertThat(periodStatus(LocalDate.of(2027, 1, 1))).isEmpty();

        Account charges = account("GL-CHARGES-AN", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("GL-COURUS-AN", AccountKind.GL, NormalBalance.CREDIT);
        Account caisse = account("GL-CAISSE-AN", AccountKind.GL, NormalBalance.DEBIT);
        Account client = savingsAccount("CLI-AN", charges, courus, "EP-AN");
        deposit(client, caisse, "36500000", SAINT_SYLVESTRE, "dep-an");

        TfjRun decembre = engine.run(ENTITY, SAINT_SYLVESTRE, ACTOR, RunMode.REAL);
        assertThat(decembre.isCompleted()).as(decembre.summary()).isTrue();

        // La bascule a prepare janvier : partition du journal et periode comptable du mois.
        assertThat(businessDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(partitionExists(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(partitionExists(LocalDate.of(2027, 3, 31))).isTrue();     // horizon de trois mois
        assertThat(periodStatus(LocalDate.of(2027, 1, 1))).contains("OPEN");
        assertThat(periodBounds(LocalDate.of(2027, 1, 15)))
            .isEqualTo("2027-01-01..2027-01-31");

        // Le premier arrete de l'annee comptabilise dans le nouveau mois : 36 500 000 a 6 % font
        // 6 000 par jour, et le 1er janvier en porte un.
        Money avant = database.inTransaction(c -> Balances.current(c, courus.id()));
        TfjRun janvier = engine.run(ENTITY, LocalDate.of(2027, 1, 1), ACTOR, RunMode.REAL);
        assertThat(janvier.isCompleted()).as(janvier.summary()).isTrue();
        Money apres = database.inTransaction(c -> Balances.current(c, courus.id()));
        assertThat(apres.minus(avant)).isEqualTo(Money.of("6000", io.corebanking.kernel.money.Currencies.XOF));
        assertThat(businessDate()).isEqualTo(LocalDate.of(2027, 1, 4));      // lundi
    }

    @Test
    @DisplayName("une periode close n'est pas rouverte par la bascule : le controle prealable la nomme")
    void a_closed_period_is_named_not_reopened() {
        LocalDate fevrier = LocalDate.of(2027, 2, 26);                          // vendredi
        database.inTransaction(c -> {
            TfjEngine.setBusinessDate(c, ENTITY, fevrier);
            Entities.openPeriod(c, ENTITY, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));
            Entities.openPeriod(c, ENTITY, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31));
            Entities.closePeriod(c, ENTITY, LocalDate.of(2027, 3, 1));
            return null;
        });

        TfjRun dernier = engine.run(ENTITY, fevrier, ACTOR, RunMode.REAL);
        assertThat(dernier.isCompleted()).as(dernier.summary()).isTrue();
        assertThat(businessDate()).isEqualTo(LocalDate.of(2027, 3, 1));

        // Mars est clos : rouvrir est une decision comptable. Le TFJ s'arrete au controle
        // prealable, en le disant, plutot que d'echouer a sa premiere ecriture.
        TfjRun mars = engine.run(ENTITY, LocalDate.of(2027, 3, 1), ACTOR, RunMode.REAL);
        assertThat(mars.isCompleted()).isFalse();
        assertThat(mars.summary()).contains("PRE_CHECKS").contains("CLOSED")
            .contains("decision comptable");
    }

    private static java.util.Optional<String> periodStatus(LocalDate date) {
        return database.inTransaction(c -> Entities.periodStatus(c, ENTITY, date));
    }

    private static boolean partitionExists(LocalDate date) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT ledger_partition_exists(?)")) {
                ps.setObject(1, date);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("controle de partition", e);
            }
        });
    }

    private static String periodBounds(LocalDate within) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT start_date, end_date FROM accounting_period"
                + " WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date")) {
                ps.setObject(1, ENTITY);
                ps.setObject(2, within);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, LocalDate.class) + ".." + rs.getObject(2, LocalDate.class);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("lecture de periode", e);
            }
        });
    }
}
