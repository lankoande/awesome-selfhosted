package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le traitement de fin de mois : un mois complet, rejoue integralement, puis clos. */
class TfmIT extends TfjTestBase {

    private static final LocalDate FIN_SEPTEMBRE = LocalDate.of(2026, 9, 30);

    private static TfjEngine tfm() {
        return StandardTfm.engine(database, postingService, calendar);
    }

    private static void arreterJusquAu(LocalDate inclus) {
        while (!businessDate().isAfter(inclus)) {
            TfjRun run = engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
            assertThat(run.isCompleted()).as(run.summary()).isTrue();
        }
    }

    private static String statut(LocalDate date) {
        return database.inTransaction(c -> Entities.periodStatus(c, ENTITY, date)).orElse("?");
    }

    private static void ecriture(String key, Account debit, Account credit, LocalDate date) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of("1", Currencies.XOF), date, null),
                    PostingLine.credit(credit.id(), Money.of("1", Currencies.XOF), date, null))));
    }

    @Test
    @DisplayName("le TFM clot septembre une fois toutes ses journees arretees, ne se rouvre pas sous un exercice clos, et son annulation rouvre en le disant")
    void september_is_closed_then_reopened() {
        // La base de test ouvre septembre et octobre d'un bloc : un mois par periode, comme en
        // exploitation.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE accounting_period SET end_date = ? WHERE legal_entity_id = ?"
                + " AND start_date = ?")) {
                ps.setObject(1, FIN_SEPTEMBRE);
                ps.setObject(2, ENTITY);
                ps.setObject(3, LocalDate.of(2026, 9, 1));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Periode de septembre", e);
            }
            Entities.openPeriod(c, ENTITY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
            return null;
        });
        Account caisse = account("CAISSE-TFM", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-TFM", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-TFM", AccountKind.GL, NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-TFM", charges, courus, "EP-TFM");
        deposit(client, caisse, "10000000", J1, "dep-tfm");

        // Un mois qui n'est pas fini ne se clot pas.
        assertThatThrownBy(() -> tfm().run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("n'est pas encore arrete jour par jour");

        arreterJusquAu(FIN_SEPTEMBRE);
        assertThat(businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));

        // Une date qui n'est pas une fin de periode ne se clot pas non plus.
        assertThatThrownBy(() -> tfm().run(ENTITY, LocalDate.of(2026, 9, 15), ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("n'est pas la fin de sa periode");

        TfjRun cloture = tfm().run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL);
        assertThat(cloture.isCompleted()).as(cloture.summary()).isTrue();
        assertThat(cloture.steps()).extracting(TfjRun.StepExecution::name)
            .containsExactly("MONTH_COMPLETE", "FULL_RECONCILIATION", "PERIOD_CLOSE");
        assertThat(statut(FIN_SEPTEMBRE)).isEqualTo("CLOSED");
        assertThat(statut(LocalDate.of(2026, 10, 1))).isEqualTo("OPEN");
        assertThat(businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));   // la date n'a pas bouge

        // Plus aucune ecriture en septembre ; octobre continue.
        assertThatThrownBy(() -> ecriture("tard-septembre", caisse, courus, FIN_SEPTEMBRE))
            .hasMessageContaining("Aucune periode comptable ouverte");
        ecriture("octobre", caisse, charges, LocalDate.of(2026, 10, 1));

        // Redemander la cloture rend le rapport existant ; la periode close est refusee a un
        // second traitement seulement si le premier a ete annule.
        assertThat(tfm().run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL).id())
            .isEqualTo(cloture.id());

        // Un mois ne se rouvre pas sous un exercice clos : la cloture annuelle s'annule d'abord.
        Account resultat = account("RESULTAT-TFM", AccountKind.GL, NormalBalance.CREDIT);
        UUID exercice = database.inTransaction(c -> FiscalYears.open(
            c, ENTITY, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), resultat.id(), ACTOR,
            APPROVER));
        database.inTransaction(c -> { FiscalYears.close(c, exercice, cloture.id()); return null; });
        assertThatThrownBy(() -> tfm().cancel(cloture.id(), ACTOR, LocalDate.of(2026, 10, 1),
                                              "ecriture oubliee"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("exercice clos");
        assertThat(statut(FIN_SEPTEMBRE)).isEqualTo("CLOSED");
        database.inTransaction(c -> { FiscalYears.reopen(c, exercice); return null; });

        // L'annulation rouvre la periode, et le dit : REOPENED n'est pas OPEN.
        tfm().cancel(cloture.id(), ACTOR, LocalDate.of(2026, 10, 1), "ecriture oubliee");
        assertThat(statut(FIN_SEPTEMBRE)).isEqualTo("REOPENED");
        ecriture("retard-septembre", caisse, charges, FIN_SEPTEMBRE);

        // Une journee de septembre annulee apres coup — l'erreur de parametrage decouverte le
        // 2 du mois. La date comptable est revenue au 30 : le moteur refuse de clore un mois
        // qui n'est pas termine.
        TfjRun dernierJour = engine.run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL);
        engine.cancel(dernierJour.id(), ACTOR, FIN_SEPTEMBRE, "erreur de parametrage");
        assertThatThrownBy(() -> tfm().run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL))
            .hasMessageContaining("n'est pas encore arrete jour par jour");

        // Et si la date a ete reprise a la main par-dessus la journee manquante, c'est l'etape
        // qui la nomme : un mois ne se clot pas sur une journee jamais arretee.
        database.inTransaction(c -> {
            TfjEngine.setBusinessDate(c, ENTITY, LocalDate.of(2026, 10, 1));
            return null;
        });
        TfjRun incomplet = tfm().run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL);
        assertThat(incomplet.status()).isEqualTo(TfjRun.Status.FAILED);
        assertThat(incomplet.failedStep().get().name()).isEqualTo("MONTH_COMPLETE");
        assertThat(String.join(" ", incomplet.failedStep().get().anomalies()))
            .contains("2026-09-30");
        assertThat(statut(FIN_SEPTEMBRE)).isEqualTo("REOPENED");

        // La journee rejouee — capitalisation comprise — le rejeu integral et les sous-livres
        // sont d'accord, et septembre se clot.
        database.inTransaction(c -> {
            TfjEngine.setBusinessDate(c, ENTITY, FIN_SEPTEMBRE);
            return null;
        });
        TfjRun rejoue = engine.run(ENTITY, FIN_SEPTEMBRE, ACTOR, RunMode.REAL);
        assertThat(rejoue.isCompleted()).as(rejoue.summary()).isTrue();
        TfjRun clos = tfm().resume(incomplet.id(), ACTOR);
        assertThat(clos.isCompleted()).as(clos.summary()).isTrue();
        assertThat(statut(FIN_SEPTEMBRE)).isEqualTo("CLOSED");
    }
}
