package io.corebanking.ledger.domain.error;

/**
 * Violation d'un invariant du ledger. Toutes les sous-classes designent une commande refusee :
 * aucune ecriture, aucun solde, aucun etat intermediaire n'a ete persiste.
 */
public abstract class LedgerViolation extends RuntimeException {
    protected LedgerViolation(String message) {
        super(message);
    }

    protected LedgerViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
