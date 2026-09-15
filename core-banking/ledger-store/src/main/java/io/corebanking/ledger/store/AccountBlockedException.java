package io.corebanking.ledger.store;

import java.util.UUID;

/** Ligne refusee : le compte est sous blocage — opposition, saisie, gel. */
public class AccountBlockedException extends RuntimeException {

    private final UUID accountId;

    public AccountBlockedException(UUID accountId, String kind, String reason) {
        super("Compte " + accountId + " sous blocage " + kind + " (" + reason + ") : l'operation "
              + "est refusee. Un blocage prime sur toute operation, y compris les prelevements "
              + "automatiques ; il se leve, il ne se contourne pas.");
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
