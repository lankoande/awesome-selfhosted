package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.deposits.Suspense;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Un compte d'attente non solde bloque la journee au-dela de l'anciennete toleree ; la revue remonte les retards. */
class TfjSuspenseIT extends TfjTestBase {

    @Test
    @DisplayName("sans politique, un compte d'attente non solde bloque des le premier arrete ; avec une tolerance, la journee passe et la revue le remonte quand il est en retard")
    void a_suspense_account_blocks_the_day_beyond_its_tolerance() {
        LocalDate jour = businessDate();                                 // lundi 14 septembre 2026
        Account caisse = account("CAISSE-SUS", AccountKind.GL, NormalBalance.DEBIT);
        Account attente = account("ATTENTE-SUS", AccountKind.SUSPENSE, NormalBalance.CREDIT);
        postingService.post(PostingCommand.online(IdempotencyKey.of("sus-1"), ENTITY, jour, "MANUAL",
            ACTOR, List.of(
                PostingLine.debit(caisse.id(), Money.of("5000", Currencies.XOF), jour, "a justifier"),
                PostingLine.credit(attente.id(), Money.of("5000", Currencies.XOF), jour, "a justifier"))));

        // Sans politique : non solde, le compte d'attente bloque.
        TfjRun refuse = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(refuse.isCompleted()).isFalse();
        assertThat(etape(refuse, "PRE_CHECKS").anomalies()).singleElement().asString()
            .contains("ATTENTE-SUS").contains("0 jour(s) ouvre(s)");

        // Avec deux jours ouvres de tolerance : la journee passe, la revue le liste sans retard.
        database.inTransaction(c -> Suspense.setPolicy(c, new Suspense.Draft(
            ENTITY, Suspense.Kind.SUSPENSE_ACCOUNT, 2, "comptabilite generale",
            LocalDate.of(2020, 1, 1), null, ACTOR, APPROVER)));
        TfjRun reprise = engine.resume(refuse.id(), ACTOR);
        assertThat(reprise.isCompleted()).as(reprise.summary()).isTrue();
        assertThat(etape(reprise, "SUSPENSE_REVIEW").read()).isEqualTo(1);
        assertThat(etape(reprise, "SUSPENSE_REVIEW").written()).isZero();
        assertThat(etape(reprise, "SUSPENSE_REVIEW").anomalies()).isEmpty();

        // Mardi, mercredi : dans la tolerance. Jeudi : trois jours ouvres, en retard — la revue
        // le dit avec son responsable, et les controles prealables refusent la journee.
        TfjRun mardi = engine.run(ENTITY, jour.plusDays(1), ACTOR, RunMode.REAL);
        assertThat(mardi.isCompleted()).as(mardi.summary()).isTrue();
        TfjRun mercredi = engine.run(ENTITY, jour.plusDays(2), ACTOR, RunMode.REAL);
        assertThat(mercredi.isCompleted()).as(mercredi.summary()).isTrue();
        assertThat(etape(mercredi, "SUSPENSE_REVIEW").anomalies()).isEmpty();
        TfjRun jeudi = engine.run(ENTITY, jour.plusDays(3), ACTOR, RunMode.REAL);
        assertThat(jeudi.isCompleted()).isFalse();
        assertThat(etape(jeudi, "PRE_CHECKS").anomalies()).singleElement().asString()
            .contains("ATTENTE-SUS").contains("3 jour(s) ouvre(s)").contains("comptabilite generale");

        // Justifie, le compte d'attente est solde : la journee passe.
        postingService.post(PostingCommand.online(IdempotencyKey.of("sus-2"), ENTITY,
            jour.plusDays(3), "MANUAL", ACTOR, List.of(
                PostingLine.debit(attente.id(), Money.of("5000", Currencies.XOF), jour.plusDays(3), "justifie"),
                PostingLine.credit(caisse.id(), Money.of("5000", Currencies.XOF), jour.plusDays(3), "justifie"))));
        TfjRun jeudiReprise = engine.resume(jeudi.id(), ACTOR);
        assertThat(jeudiReprise.isCompleted()).as(jeudiReprise.summary()).isTrue();
        assertThat(etape(jeudiReprise, "SUSPENSE_REVIEW").read()).isZero();
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
