package io.corebanking.ledger.domain.account;

public enum AccountStatus {
    ACTIVE, DORMANT, BLOCKED, CLOSING, CLOSED;

    /** Seul un compte actif ou dormant accepte une imputation. */
    public boolean acceptsPosting() {
        return this == ACTIVE || this == DORMANT;
    }
}
