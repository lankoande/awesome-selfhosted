package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingSource;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.FxPositions;
import io.corebanking.ledger.store.FxRates;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'arrete exige le cours du jour avant tout calcul, revalorise les positions apres, et son
 * annulation defait la revalorisation.
 */
class TfjFxIT extends TfjTestBase {

    @Test
    @DisplayName("sans cours du jour, la journee s'arrete aux controles ; cote, elle passe et revalorise la position au cours de cloture ; annulee, la revalorisation est contre-passee ; une devise detenue sans position declaree arrete aussi la journee")
    void the_day_needs_its_rates_and_revalues_positions() {
        LocalDate jour = businessDate();                                 // lundi 14 septembre 2026
        Account position = positionAccount("POS-USD", Currencies.USD, NormalBalance.CREDIT);
        Account contreValeur = positionAccount("CV-USD", Currencies.XOF, NormalBalance.DEBIT);
        Account gain = resultAccount("GAIN-CHANGE", NormalBalance.CREDIT);
        Account perte = resultAccount("PERTE-CHANGE", NormalBalance.DEBIT);
        Account contrepartieUsd = account("CONTREPARTIE-USD", AccountKind.GL, NormalBalance.CREDIT,
                                          Currencies.USD);
        Account contrepartieXof = account("CONTREPARTIE-XOF-FX", AccountKind.GL,
                                          NormalBalance.CREDIT, Currencies.XOF);
        database.inTransaction(c -> FxPositions.declare(c, new FxPositions.Draft(
            ENTITY, "USD", position.id(), contreValeur.id(), gain.id(), perte.id(), 100, ACTOR,
            APPROVER)));

        // Sans cours du jour, la journee s'arrete avant tout calcul.
        TfjRun sansCours = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(sansCours.isCompleted()).isFalse();
        assertThat(etape(sansCours, "FX_RATES").anomalies()).singleElement().asString()
            .contains("USD").contains("aucun cours cote au " + jour);

        // Cote, la journee passe : la banque achete 100 USD a 600, sa position vaut 60 000 XOF
        // au cours de cloture du jour — rien a revaloriser.
        coter("USD", jour, "600");
        postingService.post(PostingCommand.online(IdempotencyKey.of("fx-achat"), ENTITY, jour,
            "FX_TRADE", ACTOR,
            List.of(PostingLine.debit(contrepartieUsd.id(), dollars("100"), jour, "achat USD")
                        .withFxRate(new BigDecimal("600")),
                    PostingLine.credit(position.id(), dollars("100"), jour, "position USD")
                        .withFxRate(new BigDecimal("600")),
                    PostingLine.debit(contreValeur.id(), xof("60000"), jour, "contre-valeur"),
                    PostingLine.credit(contrepartieXof.id(), xof("60000"), jour, "contrepartie"))));
        TfjRun premiere = engine.resume(sansCours.id(), ACTOR);
        assertThat(premiere.isCompleted()).as(premiere.summary()).isTrue();
        assertThat(etape(premiere, "FX_REVALUATION").read()).isEqualTo(1);
        assertThat(etape(premiere, "FX_REVALUATION").written()).as("le cours n'a pas bouge").isZero();
        assertThat(solde(contreValeur)).isEqualTo(xof("60000"));

        // Le lendemain, le dollar monte a 610 : la revalorisation constate un gain de 1 000.
        LocalDate lendemain = businessDate();
        assertThat(lendemain).isEqualTo(jour.plusDays(1));
        coter("USD", lendemain, "610");
        TfjRun hausse = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);
        assertThat(hausse.isCompleted()).as(hausse.summary()).isTrue();
        assertThat(etape(hausse, "FX_REVALUATION").written()).isEqualTo(1);
        assertThat(solde(contreValeur)).isEqualTo(xof("61000"));
        assertThat(solde(gain)).isEqualTo(xof("1000"));
        assertThat(solde(position)).as("la quantite n'a pas bouge").isEqualTo(dollars("100"));

        // L'annulation contre-passe la revalorisation : la contre-valeur revient ou elle etait.
        engine.cancel(hausse.id(), ACTOR, lendemain.plusDays(1), "erreur de cours");
        assertThat(solde(contreValeur)).isEqualTo(xof("60000"));
        assertThat(solde(gain).isZero()).isTrue();
        assertThat(businessDate()).isEqualTo(lendemain);

        // Une devise detenue sans position declaree — une reprise de donnees, par exemple —
        // arrete aussi la journee : personne ne la revalorise.
        Account euros = account("REPRISE-EUR", AccountKind.GL, NormalBalance.CREDIT, Currencies.EUR);
        Account contrepartieEur = account("REPRISE-EUR-CP", AccountKind.GL, NormalBalance.DEBIT,
                                          Currencies.EUR);
        postingService.post(new PostingCommand(IdempotencyKey.of("fx-reprise"), ENTITY, lendemain,
            "MIGRATION", ACTOR, PostingSource.MIGRATION, null,
            List.of(PostingLine.debit(contrepartieEur.id(), Money.of("50", Currencies.EUR),
                                      lendemain, "reprise").withFxRate(new BigDecimal("655.957")),
                    PostingLine.credit(euros.id(), Money.of("50", Currencies.EUR), lendemain,
                                       "reprise").withFxRate(new BigDecimal("655.957"))),
            Map.of()));
        coter("USD", lendemain.plusDays(1), "610");
        TfjRun sansPosition = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);
        assertThat(sansPosition.isCompleted()).isFalse();
        assertThat(etape(sansPosition, "FX_RATES").anomalies()).singleElement().asString()
            .contains("EUR").contains("sans position de change declaree");
    }

    // ------------------------------------------------------------------ outillage

    private static Account positionAccount(String code, CurrencyRef currency,
                                           NormalBalance normalBalance) {
        return account(code, AccountKind.POSITION, normalBalance, currency);
    }

    private static Account resultAccount(String code, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL,
                                      normalBalance, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE, null)
            .withNature(AccountNature.PROFIT_AND_LOSS);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Account account(String code, AccountKind kind, NormalBalance normalBalance,
                                   CurrencyRef currency) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normalBalance,
                                      currency, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static void coter(String devise, LocalDate le, String cours) {
        database.inTransaction(c -> FxRates.quote(c, new FxRates.Quote(
            ENTITY, devise, le, new BigDecimal(cours), "BCEAO", ACTOR, APPROVER)));
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static Money dollars(String montant) {
        return Money.of(montant, Currencies.USD);
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
