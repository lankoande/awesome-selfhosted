package io.corebanking.tfj;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Etat d'un traitement, tel qu'il est restitue a l'exploitation. */
public record TfjRun(
    UUID id,
    UUID legalEntityId,
    LocalDate businessDate,
    RunMode mode,
    Status status,
    List<StepExecution> steps,
    Instant startedAt,
    Instant finishedAt) {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    public record StepExecution(
        int order,
        String name,
        boolean blocking,
        Status status,
        long read,
        long written,
        List<String> anomalies,
        String error) {

        public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

        public StepExecution {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    public TfjRun {
        steps = List.copyOf(steps);
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /** Etape en echec, s'il y en a une : point de reprise. */
    public java.util.Optional<StepExecution> failedStep() {
        return steps.stream()
            .filter(step -> step.status() == StepExecution.Status.FAILED)
            .findFirst();
    }

    public String summary() {
        StringBuilder sb = new StringBuilder("TFJ ").append(businessDate)
            .append(" [").append(mode).append("] : ").append(status);
        steps.forEach(step -> sb.append("\n  ").append(step.order()).append(". ")
            .append(step.name()).append(" — ").append(step.status())
            .append(" (lu ").append(step.read()).append(", ecrit ").append(step.written()).append(')')
            .append(step.anomalies().isEmpty() ? "" : "\n     anomalies : " + step.anomalies())
            .append(step.error() == null ? "" : "\n     echec : " + step.error()));
        return sb.toString();
    }
}
