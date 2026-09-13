package io.corebanking.ledger.store;

/** Defaillance technique d'acces au ledger. Distincte d'une violation d'invariant metier. */
public class LedgerStoreException extends RuntimeException {
    public LedgerStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public LedgerStoreException(String message) {
        super(message);
    }
}
