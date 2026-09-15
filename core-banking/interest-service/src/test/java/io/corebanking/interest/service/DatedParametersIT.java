package io.corebanking.interest.service;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.interest.accrual.InterestBasis;
import io.corebanking.interest.accrual.InterestCalculator;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.product.ProductCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le parametrage date, mis a l'epreuve la ou il compte : un changement de taux en cours de periode.
 *
 * <p>Sans historisation, ces deux scenarios produisent des montants faux que rien ne signale.
 */
class DatedParametersIT extends InterestTestBase {

    private static final LocalDate DEPART = BUSINESS_DATE;
    private static final UUID REDACTEUR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID VALIDEUR = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Deux versions du meme produit : 3 % les quinze premiers jours, 6 % ensuite. */
    private void publishTwoVersions(String code, Account charges, Account courus, int switchDay) {
        database.inTransaction(c -> {
            Map<String, String> commun = Map.of(
                ProductCatalog.P_DAY_COUNT, "ACT_365",
                ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name(),
                ProductCatalog.P_CAPITALISATION, "QUARTERLY",
                ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString(),
                ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());

            var v1 = new java.util.HashMap<>(commun);
            v1.put(ProductCatalog.P_RATE, "3");
            UUID premiere = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, "SAVINGS_ACCOUNT", "Epargne", "XOF",
                DEPART, DEPART.plusDays(switchDay - 1), v1, List.of(), REDACTEUR));
            ProductCatalog.activate(c, premiere, VALIDEUR);

            var v2 = new java.util.HashMap<>(commun);
            v2.put(ProductCatalog.P_RATE, "6");
            UUID seconde = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, "SAVINGS_ACCOUNT", "Epargne", "XOF",
                DEPART.plusDays(switchDay), null, v2, List.of(), REDACTEUR));
            ProductCatalog.activate(c, seconde, VALIDEUR);
            return null;
        });
    }

    private void deposit(Account client, Account caisse, String amount, LocalDate valueDate,
                         String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, BUSINESS_DATE, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), valueDate, null),
                    PostingLine.credit(client.id(), Money.of(amount, XOF), valueDate, null))));
    }

    /** Interets d'un segment de journees a solde constant et taux constant. */
    private Money segment(LocalDate from, int days, String balance, String rate) {
        List<DailyBalance> serie = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            serie.add(new DailyBalance(from.plusDays(i), Money.of(balance, XOF)));
        }
        return InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR,
                                         FlatRate.of(rate), DayCountConvention.ACT_365).total();
    }

    @Test
    @DisplayName("chaque journee est remuneree au taux en vigueur ce jour-la, pas au taux du jour du calcul")
    void each_day_uses_the_rate_in_force_that_day() {
        Account client = customer("CLI-500");
        Account caisse = gl("GL-CAISSE-500", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-500", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-500", NormalBalance.CREDIT);

        publishTwoVersions("EP-500", charges, courus, 15);
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, client.id(), "EP-500", DEPART, null);
            return null;
        });
        deposit(client, caisse, "1000000", DEPART, "dep-500");

        AccrualOutcome outcome = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(29),
            new CatalogTermsResolver(database, ENTITY, client.id()),
            BUSINESS_DATE, ACTOR, RUN);

        Money attendu = segment(DEPART, 15, "1000000", "3")
            .plus(segment(DEPART.plusDays(15), 15, "1000000", "6"));
        assertThat(outcome.cumulativePrecise()).isEqualTo(attendu);

        // Appliquer le taux du jour du calcul a tout le mois aurait donne nettement plus.
        Money sansHistorisation = segment(DEPART, 30, "1000000", "6");
        assertThat(sansHistorisation.isGreaterThan(attendu)).isTrue();

        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("un recalcul retroactif reapplique les taux d'epoque, pas celui en vigueur au recalcul")
    void retroactive_recompute_reapplies_historical_rates() {
        Account client = customer("CLI-501");
        Account caisse = gl("GL-CAISSE-501", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-501", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-501", NormalBalance.CREDIT);

        publishTwoVersions("EP-501", charges, courus, 15);
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, client.id(), "EP-501", DEPART, null);
            return null;
        });
        deposit(client, caisse, "1000000", DEPART, "dep-501-1");

        interestService.accrueThrough(ENTITY, client.id(), DEPART.plusDays(29),
            new CatalogTermsResolver(database, ENTITY, client.id()), BUSINESS_DATE, ACTOR, RUN);

        // Une operation arrive apres coup, avec une date de valeur au 21e jour.
        deposit(client, caisse, "500000", DEPART.plusDays(20), "dep-501-2");

        AccrualOutcome apres = interestService.recomputeFrom(
            ENTITY, client.id(), DEPART.plusDays(20),
            new CatalogTermsResolver(database, ENTITY, client.id()), BUSINESS_DATE, ACTOR, RUN);

        // Le recalcul doit conserver les 15 journees a 3 %, les 5 journees a 6 % sur 1 000 000,
        // et n'appliquer le solde corrige qu'a partir du 21e jour.
        Money attendu = segment(DEPART, 15, "1000000", "3")
            .plus(segment(DEPART.plusDays(15), 5, "1000000", "6"))
            .plus(segment(DEPART.plusDays(20), 10, "1500000", "6"));

        assertThat(apres.cumulativePrecise()).isEqualTo(attendu);

        database.inTransaction(c -> {
            assertThat(io.corebanking.ledger.store.Balances.current(c, courus.id()))
                .isEqualTo(attendu.roundToCurrency());
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }
}
