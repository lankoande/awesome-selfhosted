package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Le change : un cours cote a deux, un cours applique confronte au referentiel, une position
 * appariee a sa contre-valeur, et l'ecart de revalorisation porte au resultat.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FxIT extends LedgerTestBase {

    private static final BigDecimal PARITE = new BigDecimal("655.957");

    private static Account clientEur;
    private static Account clientXof;
    private static Account position;
    private static Account contreValeur;
    private static Account gain;
    private static Account perte;

    @BeforeAll
    static void decor() {
        clientEur = newGlAccount("FX-CONTREPARTIE-EUR", Currencies.EUR, NormalBalance.CREDIT, 1);
        clientXof = newGlAccount("FX-CONTREPARTIE-XOF", Currencies.XOF, NormalBalance.CREDIT, 1);
        position = positionAccount("FX-POSITION-EUR", Currencies.EUR, NormalBalance.CREDIT);
        contreValeur = positionAccount("FX-CONTRE-VALEUR", Currencies.XOF, NormalBalance.DEBIT);
        gain = resultAccount("FX-GAIN", NormalBalance.CREDIT);
        perte = resultAccount("FX-PERTE", NormalBalance.DEBIT);
    }

    private static Account positionAccount(String code, CurrencyRef currency,
                                           NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.POSITION,
                                      normalBalance, currency, true, false, 1,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Account resultAccount(String code, NormalBalance normalBalance) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL,
                                      normalBalance, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE, null)
            .withNature(AccountNature.PROFIT_AND_LOSS);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static UUID coter(String devise, LocalDate le, String cours) {
        return database.inTransaction(c -> FxRates.quote(c, new FxRates.Quote(
            ENTITY, devise, le, new BigDecimal(cours), "BCEAO", ACTOR,
            UUID.randomUUID())).id());
    }

    /** L'achat de 1 000 EUR contre XOF, tel que le dossier le decrit : quatre lignes. */
    private static UUID acheterEuros(String key, String cours, LocalDate le) {
        Money euros = Money.of("1000", Currencies.EUR);
        Money xof = Money.of(new BigDecimal(cours).multiply(new BigDecimal("1000"))
                                 .setScale(0, java.math.RoundingMode.HALF_EVEN), Currencies.XOF);
        return postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, le, "FX_TRADE", ACTOR,
            List.of(PostingLine.debit(clientEur.id(), euros, le, "achat EUR")
                        .withFxRate(new BigDecimal(cours)),
                    PostingLine.credit(position.id(), euros, le, "position EUR")
                        .withFxRate(new BigDecimal(cours)),
                    PostingLine.debit(contreValeur.id(), xof, le, "contre-valeur"),
                    PostingLine.credit(clientXof.id(), xof, le, "contrepartie XOF")))).entryId();
    }

    @Test
    @Order(1)
    @DisplayName("un cours se cote a deux, une fois par jour et par devise ; la devise de tenue ne se cote pas contre elle-meme ; un cours vaut jusqu'au suivant")
    void rates() {
        LocalDate jour = BUSINESS_DATE.minusMonths(1).withDayOfMonth(2);
        UUID id = coter("XAF", jour, "655.9570000000");
        FxRates.Rate cote = database.inTransaction(c -> FxRates.require(c, id));
        assertThat(cote.currency()).isEqualTo("XAF");
        assertThat(cote.source()).isEqualTo("BCEAO");
        assertThat(cote.rate()).isEqualByComparingTo(PARITE);

        assertThatThrownBy(() -> coter("XAF", jour, "656"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deja cote");
        assertThatThrownBy(() -> coter("XOF", jour, "1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("elle-meme");
        assertThatThrownBy(() -> database.inTransaction(c -> FxRates.quote(c, new FxRates.Quote(
                ENTITY, "XAF", jour.plusDays(1), PARITE, "BCEAO", ACTOR, ACTOR))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThatThrownBy(() -> new FxRates.Quote(ENTITY, "XAF", jour, PARITE, " ", ACTOR,
                                                   UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");

        // Un cours vaut jusqu'au suivant ; la veille de la premiere cotation, il n'y en a pas.
        coter("XAF", jour.plusDays(3), "656.5");
        java.util.Optional<FxRates.Rate> lendemain = database.inTransaction(
            c -> FxRates.latestOn(c, ENTITY, "XAF", jour.plusDays(1)));
        assertThat(lendemain).get().extracting(FxRates.Rate::rate)
            .satisfies(r -> assertThat((BigDecimal) r).isEqualByComparingTo(PARITE));
        java.util.Optional<FxRates.Rate> apres = database.inTransaction(
            c -> FxRates.latestOn(c, ENTITY, "XAF", jour.plusDays(4)));
        assertThat(apres).get().extracting(FxRates.Rate::quotedOn).isEqualTo(jour.plusDays(3));
        java.util.Optional<FxRates.Rate> avant = database.inTransaction(
            c -> FxRates.latestOn(c, ENTITY, "XAF", jour.minusDays(1)));
        assertThat(avant).isEmpty();
        List<FxRates.Rate> cours = database.inTransaction(c -> FxRates.rates(c, ENTITY, "XAF", 10));
        assertThat(cours).hasSize(2);
    }

    @Test
    @Order(2)
    @DisplayName("une position apparie deux comptes de nature POSITION, position creditrice et contre-valeur debitrice, avec des comptes de resultat en devise de tenue")
    void positions() {
        FxPositions.Draft draft = new FxPositions.Draft(ENTITY, "EUR", position.id(),
            contreValeur.id(), gain.id(), perte.id(), 200, ACTOR, UUID.randomUUID());
        FxPositions.Position declaree = database.inTransaction(c -> FxPositions.declare(c, draft));
        assertThat(declaree.currency()).isEqualTo("EUR");
        assertThat(declaree.toleranceBps()).isEqualTo(200);
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c, draft)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deja declaree");

        // Le sens des deux comptes est impose, et leur nature aussi.
        Account inverse = positionAccount("FX-POSITION-INVERSE", Currencies.USD, NormalBalance.DEBIT);
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c,
                new FxPositions.Draft(ENTITY, "USD", inverse.id(), contreValeur.id(), gain.id(),
                                      perte.id(), 0, ACTOR, UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Position creditrice, contre-valeur debitrice");
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c,
                new FxPositions.Draft(ENTITY, "EUR", clientEur.id(), contreValeur.id(), gain.id(),
                                      perte.id(), 0, ACTOR, UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nature POSITION");
        // Le compte de position est tenu dans la devise de la position.
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c,
                new FxPositions.Draft(ENTITY, "XAF", position.id(), contreValeur.id(), gain.id(),
                                      perte.id(), 0, ACTOR, UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("est tenu en EUR");
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c,
                new FxPositions.Draft(ENTITY, "XOF", position.id(), contreValeur.id(), gain.id(),
                                      perte.id(), 0, ACTOR, UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("etalon");
        Account produitOrdinaire = newGlAccount("FX-PRODUIT-BILAN", Currencies.XOF,
                                                NormalBalance.CREDIT, 1);
        Account autrePosition = positionAccount("FX-POSITION-XAF", Currencies.XAF,
                                                NormalBalance.CREDIT);
        assertThatThrownBy(() -> database.inTransaction(c -> FxPositions.declare(c,
                new FxPositions.Draft(ENTITY, "XAF", autrePosition.id(), contreValeur.id(),
                                      produitOrdinaire.id(), perte.id(), 0, ACTOR,
                                      UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compte de resultat");
    }

    @Test
    @Order(3)
    @DisplayName("un cours applique se confronte au referentiel : sans position declaree, sans cours, ou au-dela de la marge, l'ecriture est refusee ; une contre-passation garde le cours d'origine")
    void the_applied_rate_is_checked_against_the_reference() {
        LocalDate jour = BUSINESS_DATE;
        // Sans position declaree en USD, une ecriture en USD n'entre pas.
        Account clientUsd = newGlAccount("FX-CLIENT-USD", Currencies.USD, NormalBalance.CREDIT, 1);
        Account contrepartieUsd = newGlAccount("FX-CONTREPARTIE-USD", Currencies.USD,
                                               NormalBalance.CREDIT, 1);
        assertThatThrownBy(() -> postingService.post(PostingCommand.online(
                IdempotencyKey.of("fx-usd"), ENTITY, jour, "FX_TRADE", ACTOR,
                List.of(PostingLine.debit(clientUsd.id(), Money.of("10", Currencies.USD), jour, null)
                            .withFxRate(new BigDecimal("600")),
                        PostingLine.credit(contrepartieUsd.id(), Money.of("10", Currencies.USD),
                                           jour, null).withFxRate(new BigDecimal("600"))))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("Aucune position de change declaree en USD");

        // En EUR, la position existe (declaree par le cas precedent), mais pas le cours du jour.
        database.inTransaction(c -> FxPositions.find(c, ENTITY, "EUR")
            .orElseGet(() -> FxPositions.declare(c, new FxPositions.Draft(ENTITY, "EUR",
                position.id(), contreValeur.id(), gain.id(), perte.id(), 200, ACTOR,
                UUID.randomUUID()))));
        assertThatThrownBy(() -> acheterEuros("fx-sans-cours", "655.957", jour))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("Aucun cours de reference en EUR");

        // Cours cote : la marge de 200 points de base laisse passer 655,957 ± 13,11.
        coter("EUR", jour, "655.957");
        UUID entree = acheterEuros("fx-1", "660", jour);
        assertThat(entree).isNotNull();
        assertThatThrownBy(() -> acheterEuros("fx-2", "680", jour))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("depasse la marge de 200 points de base");

        // La contre-passation garde le cours d'origine, meme si le referentiel a bouge.
        coter("EUR", jour.plusDays(1), "700");
        UUID extourne = postingService.reverse(entree, jour, jour.plusDays(1),
            IdempotencyKey.of("fx-1-extourne"), "erreur de saisie").entryId();
        assertThat(extourne).isNotNull();
        Money soldePosition = database.inTransaction(c -> Balances.current(c, position.id()));
        assertThat(soldePosition.isZero()).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("la contre-valeur portee se rapproche de la contre-valeur historique de la position : une jambe oubliee ou deux cours differents se voient au rapprochement, la ou le ledger ne voit rien")
    void the_counter_value_is_reconciled_with_the_position() {
        LocalDate jour = BUSINESS_DATE.plusDays(3);
        Account positionXaf = positionAccount("FX-POSITION-XAF-REC", Currencies.XAF,
                                              NormalBalance.CREDIT);
        Account contreValeurXaf = positionAccount("FX-CV-XAF-REC", Currencies.XOF,
                                                  NormalBalance.DEBIT);
        Account contrepartieXaf = newGlAccount("FX-CP-XAF-REC", Currencies.XAF,
                                               NormalBalance.CREDIT, 1);
        Account contrepartieXof = newGlAccount("FX-CP-XOF-REC", Currencies.XOF,
                                               NormalBalance.CREDIT, 1);
        database.inTransaction(c -> FxPositions.declare(c, new FxPositions.Draft(
            ENTITY, "XAF", positionXaf.id(), contreValeurXaf.id(), gain.id(), perte.id(), 500,
            ACTOR, UUID.randomUUID())));
        coter("XAF", jour, "1");

        // Deux cours differents dans une meme ecriture, chacun sur une paire equilibree : les
        // contre-valeurs se compensent deux a deux, l'ecriture est equilibree par devise comme en
        // contre-valeur — c'est le cas que le ledger ne voit pas, et que le referentiel refuse.
        Money mille = Money.of("1000", Currencies.XAF);
        Money cinqCents = Money.of("500", Currencies.XAF);
        assertThatThrownBy(() -> postingService.post(PostingCommand.online(
                IdempotencyKey.of("rec-deux-cours"), ENTITY, jour, "FX_TRADE", ACTOR,
                List.of(PostingLine.debit(contrepartieXaf.id(), mille, jour, null)
                            .withFxRate(BigDecimal.ONE),
                        PostingLine.credit(positionXaf.id(), mille, jour, null)
                            .withFxRate(BigDecimal.ONE),
                        PostingLine.debit(positionXaf.id(), cinqCents, jour, null)
                            .withFxRate(new BigDecimal("2")),
                        PostingLine.credit(contrepartieXaf.id(), cinqCents, jour, null)
                            .withFxRate(new BigDecimal("2"))))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("depasse la marge de 500 points de base");

        // L'operation reguliere : la position et sa contre-valeur bougent ensemble.
        postingService.post(PostingCommand.online(IdempotencyKey.of("rec-1"), ENTITY, jour,
            "FX_TRADE", ACTOR,
            List.of(PostingLine.debit(contrepartieXaf.id(), mille, jour, "achat XAF")
                        .withFxRate(BigDecimal.ONE),
                    PostingLine.credit(positionXaf.id(), mille, jour, "position XAF")
                        .withFxRate(BigDecimal.ONE),
                    PostingLine.debit(contreValeurXaf.id(), Money.of("1000", Currencies.XOF), jour,
                                      "contre-valeur"),
                    PostingLine.credit(contrepartieXof.id(), Money.of("1000", Currencies.XOF), jour,
                                       "contrepartie"))));
        List<Reconciliation.Discrepancy> apresOperation = database.inTransaction(
            c -> Reconciliation.fxCounterValueMatchesPosition(c, ENTITY));
        assertThat(apresOperation).isEmpty();

        // La jambe de contre-valeur oubliee : l'ecriture est equilibree par devise et en
        // contre-valeur, le ledger l'accepte — et le rapprochement la denonce.
        postingService.post(PostingCommand.online(IdempotencyKey.of("rec-2"), ENTITY, jour,
            "FX_TRADE", ACTOR,
            List.of(PostingLine.debit(contrepartieXaf.id(), mille, jour, "achat sans contre-valeur")
                        .withFxRate(BigDecimal.ONE),
                    PostingLine.credit(positionXaf.id(), mille, jour, "position XAF")
                        .withFxRate(BigDecimal.ONE))));
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.fxCounterValueMatchesPosition(c, ENTITY));
        assertThat(ecarts).singleElement().satisfies(ecart -> {
            assertThat(ecart.check()).isEqualTo("POSITION_CHANGE");
            assertThat(ecart.scope()).isEqualTo("XAF");
            assertThat(ecart.gap()).isEqualByComparingTo("-1000");
        });
    }

    @Test
    @Order(4)
    @DisplayName("la revalorisation porte l'ecart au resultat de change : rien quand le cours ne bouge pas, un gain quand la devise monte, une perte quand elle baisse ; rejouee sous le meme traitement, elle ne comptabilise pas deux fois")
    void revaluation() {
        LocalDate jour = BUSINESS_DATE;
        Account positionUsd = positionAccount("FX-POSITION-USD-REV", Currencies.USD,
                                              NormalBalance.CREDIT);
        Account contreValeurUsd = positionAccount("FX-CV-USD-REV", Currencies.XOF,
                                                  NormalBalance.DEBIT);
        Account clientUsd = newGlAccount("FX-CLIENT-USD-REV", Currencies.USD,
                                         NormalBalance.CREDIT, 1);
        Account contrepartie = newGlAccount("FX-CONTREPARTIE-USD-REV", Currencies.XOF,
                                            NormalBalance.CREDIT, 1);
        database.inTransaction(c -> FxPositions.declare(c, new FxPositions.Draft(
            ENTITY, "USD", positionUsd.id(), contreValeurUsd.id(), gain.id(), perte.id(), 100,
            ACTOR, UUID.randomUUID())));
        coter("USD", jour, "600");

        // La banque achete 100 USD a 600 : sa position est longue de 100 USD, sa contre-valeur
        // de 60 000 XOF.
        Money dollars = Money.of("100", Currencies.USD);
        Money xof = Money.of("60000", Currencies.XOF);
        postingService.post(PostingCommand.online(IdempotencyKey.of("rev-1"), ENTITY, jour,
            "FX_TRADE", ACTOR,
            List.of(PostingLine.debit(clientUsd.id(), dollars, jour, "achat USD")
                        .withFxRate(new BigDecimal("600")),
                    PostingLine.credit(positionUsd.id(), dollars, jour, "position USD")
                        .withFxRate(new BigDecimal("600")),
                    PostingLine.debit(contreValeurUsd.id(), xof, jour, "contre-valeur"),
                    PostingLine.credit(contrepartie.id(), xof, jour, "contrepartie"))));
        assertThat(solde(positionUsd)).isEqualTo(dollars);
        assertThat(solde(contreValeurUsd)).isEqualTo(xof);

        FxRevaluation revaluation = new FxRevaluation(database, postingService);
        Money gainAvant = solde(gain);
        Money perteAvant = solde(perte);

        // Au cours du jour, rien n'a bouge : aucune ecriture.
        FxRevaluation.Result stable = resultat(revaluation.revalue(ENTITY, jour, UUID.randomUUID(),
                                                                   ACTOR));
        assertThat(stable.delta().isZero()).isTrue();
        assertThat(stable.entryId()).isNull();
        assertThat(solde(contreValeurUsd)).isEqualTo(xof);

        // Le dollar monte a 610 : la position vaut 61 000, elle en portait 60 000 — gain de 1 000.
        coter("USD", jour.plusDays(1), "610");
        UUID runHausse = UUID.randomUUID();
        FxRevaluation.Result hausse = resultat(revaluation.revalue(ENTITY, jour.plusDays(1),
                                                                   runHausse, ACTOR));
        assertThat(hausse.delta()).isEqualTo(Money.of("1000", Currencies.XOF));
        assertThat(hausse.revaluedValue()).isEqualTo(Money.of("61000", Currencies.XOF));
        assertThat(hausse.entryId()).isNotNull();
        assertThat(solde(contreValeurUsd)).isEqualTo(Money.of("61000", Currencies.XOF));
        assertThat(solde(gain)).isEqualTo(gainAvant.plus(Money.of("1000", Currencies.XOF)));
        assertThat(solde(positionUsd)).as("la quantite n'a pas bouge, sa valeur si").isEqualTo(dollars);

        // Rejouee sous le meme traitement, elle ne comptabilise pas deux fois.
        FxRevaluation.Result rejeu = resultat(revaluation.revalue(ENTITY, jour.plusDays(1),
                                                                  runHausse, ACTOR));
        assertThat(rejeu.delta().isZero()).isTrue();
        assertThat(solde(contreValeurUsd)).isEqualTo(Money.of("61000", Currencies.XOF));

        // Le dollar retombe a 590 : la position vaut 59 000 — perte de 2 000 depuis 61 000.
        // L'euro est cote le meme jour : l'arrete revalorise toutes les positions, ou aucune.
        coter("USD", jour.plusDays(2), "590");
        coter("EUR", jour.plusDays(2), "655.957");
        FxRevaluation.Result baisse = resultat(revaluation.revalue(ENTITY, jour.plusDays(2),
                                                                   UUID.randomUUID(), ACTOR));
        assertThat(baisse.delta()).isEqualTo(Money.of("-2000", Currencies.XOF));
        assertThat(solde(contreValeurUsd)).isEqualTo(Money.of("59000", Currencies.XOF));
        assertThat(solde(perte)).isEqualTo(perteAvant.plus(Money.of("2000", Currencies.XOF)));

        // Sans cours du jour, la position ne se revalorise pas au cours d'un autre jour.
        assertThatThrownBy(() -> revaluation.revalue(ENTITY, jour.plusDays(3), UUID.randomUUID(),
                                                     ACTOR))
            .isInstanceOf(LedgerStoreException.class).hasMessageContaining("Aucun cours du");
        List<FxRevaluation.Gap> manques = database.inTransaction(
            c -> FxRevaluation.gaps(c, ENTITY, jour.plusDays(3)));
        assertThat(manques).extracting(FxRevaluation.Gap::currency).contains("USD");

        // L'exposition se lit : solde, contre-valeur portee, cours, ecart latent.
        FxPositions.Exposure exposition = database.inTransaction(c -> FxPositions.exposure(
            c, FxPositions.find(c, ENTITY, "USD").orElseThrow(), jour.plusDays(2)));
        assertThat(exposition.balance()).isEqualTo(dollars);
        assertThat(exposition.carriedValue()).isEqualTo(Money.of("59000", Currencies.XOF));
        assertThat(exposition.unrealised().isZero()).as("revalorisee le jour meme").isTrue();
        List<io.corebanking.ledger.store.Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, ENTITY));
        assertThat(ecarts).isEmpty();
    }

    private static FxRevaluation.Result resultat(List<FxRevaluation.Result> resultats) {
        return resultats.stream().filter(r -> "USD".equals(r.currency())).findFirst().orElseThrow();
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }
}
