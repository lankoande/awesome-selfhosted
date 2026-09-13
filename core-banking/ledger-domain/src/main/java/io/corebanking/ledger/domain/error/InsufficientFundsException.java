package io.corebanking.ledger.domain.error;

import io.corebanking.kernel.money.Money;
import java.util.UUID;

/** Disponible insuffisant sur un compte a controle de solde. */
public class InsufficientFundsException extends LedgerViolation {

    private final transient UUID accountId;
    private final transient Money available;
    private final transient Money requested;

    public InsufficientFundsException(UUID accountId, Money available, Money requested) {
        super("Disponible insuffisant sur le compte " + accountId
              + " : disponible " + available + ", demande " + requested);
        this.accountId = accountId;
        this.available = available;
        this.requested = requested;
    }

    public UUID accountId()  { return accountId; }
    public Money available() { return available; }
    public Money requested() { return requested; }
}
