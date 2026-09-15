package io.corebanking.api.usecase;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.deposits.TillService;
import io.corebanking.deposits.Tills;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Caller;
import io.corebanking.security.Operation;
import io.corebanking.security.UseCase;
import java.util.UUID;

/**
 * Caisses. Le guichetier ne choisit pas sa caisse : elle est la sienne, resolue depuis le sujet
 * de son jeton ; l'arrete de caisse porte le comptage, et la politique dit qui arrete quoi.
 */
public final class TillUseCases {

    private TillUseCases() {}

    public static Tills.Till require(Database database, UUID tillId) {
        return database.inTransaction(c -> Tills.find(c, tillId))
            .orElseThrow(() -> new Tills.UnknownTillException(tillId));
    }

    /** La caisse de l'appelant, ou un refus nomme : une operation de caisse se fait sur la sienne. */
    public static Tills.Till ofCaller(Database database, Caller caller) {
        return database.inTransaction(
                c -> Tills.forTeller(c, caller.legalEntityId(), caller.subjectId()))
            .orElseThrow(() -> new NoTillException(caller.username()));
    }

    public static class NoTillException extends RuntimeException {
        public NoTillException(String username) {
            super("Aucune caisse n'est affectee a " + username
                  + " : une operation de caisse se fait sur sa propre caisse.");
        }
    }

    public record Closing(UUID tillId, String counted, String currency, UUID actorId) {}

    public static final class Close implements UseCase<Closing, TillService.Closure> {
        private final Database database;
        private final AccountDirectory accounts;
        private final TillService tills;

        public Close(Database database, AccountDirectory accounts, TillService tills) {
            this.database = database;
            this.accounts = accounts;
            this.tills = tills;
        }

        @Override public Operation operation() { return Operation.TILL_CLOSE; }

        @Override
        public AccessTarget targetOf(Closing command) {
            Tills.Till till = require(database, command.tillId());
            // Le titulaire vient de la caisse, jamais de la requete : un guichetier n'arrete
            // que la sienne, le chef d'agence toute caisse de son agence.
            return AccessTarget.inBranch(till.legalEntityId(), till.branchId())
                .ownedBy(till.tellerSubjectId());
        }

        @Override
        public TillService.Closure execute(Closing command) {
            Tills.Till till = require(database, command.tillId());
            Account cash = accounts.require(till.cashAccountId());
            Money counted = Amounts.in(command.counted(), command.currency(), cash.currency(),
                                       "La caisse " + till.code());
            return tills.close(new TillService.Closing(till.id(), counted, command.actorId()));
        }
    }
}
