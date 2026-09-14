package io.corebanking.benchmark;

import static io.corebanking.kernel.money.Currencies.XOF;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Reconciliation;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Debit et latence de la comptabilisation.
 *
 * <p>Cible du dossier : 1 500 ecritures par seconde soutenues, centile 99 sous 150 ms, sans
 * interblocage et sans ecart de reconciliation.
 *
 * <pre>mvn test -pl benchmark -Dtest=LedgerBenchmark -Dbench.accounts=2000 -Dbench.ops=8000</pre>
 */
class LedgerBenchmark extends BenchmarkBase {

    @Test
    @DisplayName("debit de comptabilisation sous concurrence")
    void posting_throughput() throws Exception {
        int accountCount = sizing("accounts", 2000);
        int operations = sizing("ops", 6000);
        int threads = sizing("threads", 8);
        int stripes = sizing("stripes", 64);

        Account caisse = gl("GL-CAISSE", NormalBalance.DEBIT, stripes);
        List<Account> clients = seedCustomers(accountCount, caisse, "10000000");
        analyze();

        line("");
        line("=== Comptabilisation ===");
        line("comptes " + accountCount + ", operations " + operations + ", fils " + threads
             + ", stripes caisse " + stripes);

        Measurement measurement = new Measurement(operations);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int perThread = operations / threads;

        long start = System.currentTimeMillis();
        for (Future<Void> future : pool.invokeAll(tasks(threads, perThread, clients, measurement))) {
            future.get(10, TimeUnit.MINUTES);
        }
        long elapsed = System.currentTimeMillis() - start;
        pool.shutdown();

        line(measurement.report("virements", elapsed));
        line("cout unitaire : " + String.format("%.2f", elapsed * 1.0 / measurement.count())
             + " ms par ecriture");

        long reconciliationStart = System.currentTimeMillis();
        List<Reconciliation.Discrepancy> discrepancies = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, ENTITY));
        line("reconciliation : " + discrepancies.size() + " ecart(s) en "
             + (System.currentTimeMillis() - reconciliationStart) + " ms");

        org.assertj.core.api.Assertions.assertThat(discrepancies).isEmpty();
        org.assertj.core.api.Assertions.assertThat(measurement.failures()).isZero();
    }

    private List<Callable<Void>> tasks(int threads, int perThread, List<Account> clients,
                                       Measurement measurement) {
        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadIndex = t;
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    int from = ThreadLocalRandom.current().nextInt(clients.size());
                    int to = ThreadLocalRandom.current().nextInt(clients.size());
                    if (from == to) {
                        continue;
                    }
                    long begin = System.nanoTime();
                    try {
                        postingService.post(PostingCommand.online(
                            IdempotencyKey.of("bench-" + threadIndex + "-" + i), ENTITY, DAY,
                            "TRANSFER", ACTOR,
                            List.of(
                                PostingLine.debit(clients.get(from).id(), Money.of("1000", XOF),
                                                  DAY, null),
                                PostingLine.credit(clients.get(to).id(), Money.of("1000", XOF),
                                                   DAY, null))));
                        measurement.record(System.nanoTime() - begin);
                    } catch (RuntimeException e) {
                        if (String.valueOf(e.getMessage()).contains("deadlock")
                            || String.valueOf(e.getCause()).contains("deadlock")) {
                            measurement.conflict();
                        } else {
                            measurement.failure();
                        }
                    }
                }
                return null;
            });
        }
        return tasks;
    }
}
