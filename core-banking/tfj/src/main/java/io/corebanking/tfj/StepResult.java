package io.corebanking.tfj;

import java.util.List;

/**
 * Resultat d'une etape.
 *
 * @param anomalies constats qui n'interrompent pas l'etape mais doivent etre restitues. Sur une
 *                  etape bloquante, une anomalie arrete le traitement ; sur les autres, elle est
 *                  consignee et le TFJ se poursuit.
 */
public record StepResult(long read, long written, List<String> anomalies) {

    public StepResult {
        anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
    }

    public static StepResult of(long read, long written) {
        return new StepResult(read, written, List.of());
    }

    public static StepResult none() {
        return new StepResult(0, 0, List.of());
    }

    public boolean hasAnomalies() {
        return !anomalies.isEmpty();
    }
}
