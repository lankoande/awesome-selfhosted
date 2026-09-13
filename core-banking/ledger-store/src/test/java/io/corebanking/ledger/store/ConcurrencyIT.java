package io.corebanking.ledger.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comportement sous concurrence reelle.
 *
 * <p>Ce sont les tests qui decident si un ledger tient en production. Un noyau comptable correct en
 * mono-thread et faux sous charge est un noyau faux : les erreurs qu'il produit sont des soldes
 * clients, et elles apparaissent le jour de la mise en service.
 */
class ConcurrencyIT extends LedgerTestBase {

    @Test
    @DisplayName("des virements croises simultanes ne provoquent aucun interblocage")
    void cross_transfers_never_deadlock() throws Exception {
        Account a = newCustomerAccount("CLI-300-A", XOF);
        Account b = newCustomerAccount("CLI-300-B", XOF);
        Account caisse = newGlAccount("GL-CAISSE-300", XOF, NormalBalance.DEBIT, 1);
        fund(a, caisse, "1000000", "fund-300-a");
        fund(b, caisse, "1000000", "fund-300-b");

        int rounds = 150;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger failures = new AtomicInteger();

        // Sans ordre de verrouillage total, ces deux flots se bloquent mutuellement des les
        // premieres iterations : A verrouille a puis attend b, B verrouille b puis attend a.
        Future<?> f1 = pool.submit(() -> transferRepeatedly(a, b, rounds, "ab", failures));
        Future<?> f2 = pool.submit(() -> transferRepeatedly(b, a, rounds, "ba", failures));
        f1.get(120, TimeUnit.SECONDS);
        f2.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(failures.get()).isZero();
        database.inTransaction(c -> {
            assertThat(Balances.current(c, a.id()).plus(Balances.current(c, b.id())))
                .isEqualTo(Money.of("2000000", XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("le disponible ne devient jamais negatif, quel que soit le parallelisme")
    void available_balance_never_goes_negative() throws Exception {
        Account client = newCustomerAccount("CLI-301", XOF);
        Account caisse = newGlAccount("GL-CAISSE-301", XOF, NormalBalance.DEBIT, 1);
        fund(client, caisse, "100000", "fund-301");

        // 200 retraits de 1 000 lances en parallele sur un solde de 100 000 : exactement 100
        // doivent passer. Un ledger qui lit le solde hors verrou en laisse passer davantage.
        int attempts = 200;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String key = "wdr-301-" + i;
            tasks.add(() -> {
                try {
                    postingService.post(PostingCommand.online(
                        IdempotencyKey.of(key), ENTITY, BUSINESS_DATE, "WITHDRAWAL", ACTOR,
                        List.of(PostingLine.debit(client.id(), Money.of("1000", XOF), BUSINESS_DATE, null),
                                PostingLine.credit(caisse.id(), Money.of("1000", XOF), BUSINESS_DATE, null))));
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) succeeded++;
        }

        assertThat(succeeded).isEqualTo(100);
        database.inTransaction(c -> {
            assertThat(Balances.current(c, client.id())).isEqualTo(Money.zero(XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("un compte chaud reparti sur 32 stripes reste exact sous contention")
    void hot_account_stays_exact_under_contention() throws Exception {
        // Compte de produits d'interets : impute par toutes les operations. Sans repartition, il
        // serialiserait l'integralite du trafic ; le debit du systeme deviendrait celui d'une ligne.
        Account produits = newGlAccount("GL-PRODUITS-302", XOF, NormalBalance.CREDIT, 32);
        Account client = newCustomerAccount("CLI-302", XOF);
        Account caisse = newGlAccount("GL-CAISSE-302", XOF, NormalBalance.DEBIT, 1);

        int threads = 8;
        int perThread = 40;
        fund(client, caisse, String.valueOf(threads * perThread * 100L), "fund-302");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    postingService.post(PostingCommand.online(
                        IdempotencyKey.of("fee-302-" + threadIndex + "-" + i),
                        ENTITY, BUSINESS_DATE, "FEE", ACTOR,
                        List.of(
                            PostingLine.debit(client.id(), Money.of("100", XOF), BUSINESS_DATE, null),
                            PostingLine.credit(produits.id(), Money.of("100", XOF), BUSINESS_DATE, null))));
                }
                return null;
            });
        }
        for (Future<Void> future : pool.invokeAll(tasks)) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Money expected = Money.of(threads * perThread * 100L, XOF);
        database.inTransaction(c -> {
            // Le solde agrege sur les 32 stripes est exact...
            assertThat(Balances.current(c, produits.id())).isEqualTo(expected);
            // ... et il coincide avec le rejeu depuis le journal.
            assertThat(Balances.replayAsOfBookingDate(c, produits.id(), BUSINESS_DATE))
                .isEqualTo(expected);
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    // ------------------------------------------------------------------ utilitaires

    private void fund(Account account, Account caisse, String amount, String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, BUSINESS_DATE, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), BUSINESS_DATE, null),
                    PostingLine.credit(account.id(), Money.of(amount, XOF), BUSINESS_DATE, null))));
    }

    private void transferRepeatedly(Account from, Account to, int rounds, String prefix,
                                    AtomicInteger failures) {
        for (int i = 0; i < rounds; i++) {
            try {
                postingService.post(PostingCommand.online(
                    IdempotencyKey.of("trf-300-" + prefix + "-" + i), ENTITY, BUSINESS_DATE,
                    "TRANSFER", ACTOR,
                    List.of(PostingLine.debit(from.id(), Money.of("1000", XOF), BUSINESS_DATE, null),
                            PostingLine.credit(to.id(), Money.of("1000", XOF), BUSINESS_DATE, null))));
            } catch (RuntimeException e) {
                failures.incrementAndGet();
            }
        }
    }
}
