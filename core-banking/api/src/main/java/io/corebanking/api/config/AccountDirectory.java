package io.corebanking.api.config;

import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import java.util.Set;
import java.util.UUID;

/** Ce que les cas d'usage doivent savoir d'un compte pour nommer la cible d'une habilitation. */
public final class AccountDirectory {

    private final Database database;

    public AccountDirectory(Database database) {
        this.database = database;
    }

    /** Le compte, ou une erreur nommee : un identifiant inconnu n'est pas une exception technique. */
    public Account require(UUID accountId) {
        Account account = database.inTransaction(
            c -> Accounts.loadAll(c, Set.of(accountId)).get(accountId));
        if (account == null) {
            throw new UnknownAccountException(accountId);
        }
        return account;
    }

    public static class UnknownAccountException extends RuntimeException {
        public UnknownAccountException(UUID accountId) {
            super("Compte inconnu : " + accountId);
        }
    }
}
