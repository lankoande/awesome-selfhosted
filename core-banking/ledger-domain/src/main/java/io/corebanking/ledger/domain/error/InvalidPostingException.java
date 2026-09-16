package io.corebanking.ledger.domain.error;

/** Commande de comptabilisation refusee : structure, compte, devise, echelle ou periode. */
public class InvalidPostingException extends LedgerViolation {
    public InvalidPostingException(String message) {
        super(message);
    }

    public InvalidPostingException(String message, Throwable cause) {
        super(message, cause);
    }
}
