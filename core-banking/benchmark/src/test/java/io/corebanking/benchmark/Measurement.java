package io.corebanking.benchmark;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collecte des latences et calcul des percentiles.
 *
 * <p>La moyenne est inutile ici : elle masque exactement ce qui compte. Un debit moyen correct avec
 * un centile 99 a deux secondes se traduit, au guichet, par une file d'attente reelle. Les cibles
 * du dossier sont donc exprimees en p99, et c'est ce qui est mesure.
 */
final class Measurement {

    private final long[] samples;
    private final AtomicInteger index = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger conflicts = new AtomicInteger();

    Measurement(int capacity) {
        this.samples = new long[capacity];
    }

    void record(long nanos) {
        int position = index.getAndIncrement();
        if (position < samples.length) {
            samples[position] = nanos;
        }
    }

    void failure() {
        failures.incrementAndGet();
    }

    void conflict() {
        conflicts.incrementAndGet();
    }

    int count() {
        return Math.min(index.get(), samples.length);
    }

    int failures() {
        return failures.get();
    }

    int conflicts() {
        return conflicts.get();
    }

    /** Percentile en millisecondes. */
    double percentileMillis(double percentile) {
        int size = count();
        if (size == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int position = (int) Math.ceil(percentile / 100.0 * size) - 1;
        return sorted[Math.max(0, Math.min(position, size - 1))] / 1_000_000.0;
    }

    String report(String label, long elapsedMillis) {
        int size = count();
        double throughput = elapsedMillis == 0 ? 0 : size * 1000.0 / elapsedMillis;
        return String.format(
            "%-28s %7d ops  %8.1f ms  %7.0f op/s  p50 %6.1f  p95 %6.1f  p99 %6.1f  "
            + "echecs %d  conflits %d",
            label, size, (double) elapsedMillis, throughput,
            percentileMillis(50), percentileMillis(95), percentileMillis(99),
            failures(), conflicts());
    }
}
