package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.deposits.TillService;
import io.corebanking.deposits.Tills;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'arrete de caisse precede l'arrete de la banque, et le conditionne. */
class TfjTillIT extends TfjTestBase {

    @Test
    @DisplayName("une caisse mouvementee et non arretee bloque la journee ; une fois arretee, la reprise passe")
    void an_unclosed_till_blocks_the_day() {
        LocalDate jour = businessDate();
        Account caisse = account("CAISSE-T1", AccountKind.INTERNAL, NormalBalance.DEBIT);
        Account client = account("CLI-T1", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Account ecarts = account("ECARTS-T1", AccountKind.GL, NormalBalance.DEBIT);
        UUID till = database.inTransaction(c -> Tills.create(c, new Tills.Draft(
            ENTITY, "T-01", caisse.id(), "guichetier-t1", ecarts.id(), ACTOR, APPROVER)));
        deposit(client, caisse, "250000", jour, "til-dep-1");

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.status()).as(run.summary()).isEqualTo(TfjRun.Status.FAILED);
        TfjRun.StepExecution controles = run.steps().get(0);
        assertThat(controles.name()).isEqualTo("PRE_CHECKS");
        assertThat(controles.status()).isEqualTo(TfjRun.StepExecution.Status.FAILED);
        assertThat(controles.anomalies()).anySatisfy(anomalie -> assertThat(anomalie).contains("T-01"));
        assertThat(businessDate()).isEqualTo(jour);

        // Le guichetier compte juste : la journee de caisse est close, celle de la banque peut l'etre.
        TillService.Closure arrete = new TillService(database, postingService).close(
            new TillService.Closing(till, Money.of("250000", Currencies.XOF), ACTOR));
        assertThat(arrete.difference().isZero()).isTrue();

        TfjRun reprise = engine.resume(run.id(), ACTOR);

        assertThat(reprise.isCompleted()).as(reprise.summary()).isTrue();
        assertThat(businessDate()).isAfter(jour);
    }
}
