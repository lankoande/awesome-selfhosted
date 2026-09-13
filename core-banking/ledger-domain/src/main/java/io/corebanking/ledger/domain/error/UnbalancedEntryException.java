package io.corebanking.ledger.domain.error;

import io.corebanking.kernel.money.Money;
import java.util.Map;
import java.util.stream.Collectors;

/** Ecriture dont les debits et les credits ne s'equilibrent pas, dans au moins une devise. */
public class UnbalancedEntryException extends LedgerViolation {

    private final transient Map<String, Money> imbalances;

    public UnbalancedEntryException(Map<String, Money> imbalances) {
        super("Ecriture desequilibree : "
              + imbalances.entrySet().stream()
                  .map(e -> "ecart de " + e.getValue() + " en " + e.getKey())
                  .collect(Collectors.joining(", ")));
        this.imbalances = Map.copyOf(imbalances);
    }

    /** Ecart par devise, debits moins credits. */
    public Map<String, Money> imbalances() {
        return imbalances;
    }
}
