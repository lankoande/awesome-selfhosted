package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.BlockKind;
import io.corebanking.deposits.Holds;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ouverture, consultation, blocages, blocages de montant, cloture. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/accounts")
public class AccountController {

    private final UseCaseExecutor executor;
    private final Database database;
    private final AccountDirectory accounts;
    private final AccountUseCases.Open open;
    private final AccountUseCases.Close close;
    private final AccountUseCases.Block block;
    private final AccountUseCases.Unblock unblock;
    private final AccountUseCases.PlaceHold placeHold;
    private final AccountUseCases.ReleaseHold releaseHold;
    private final AccountUseCases.ReadBalance readBalance;

    public AccountController(UseCaseExecutor executor, Database database,
                             AccountLifecycle lifecycle, AccountDirectory accounts) {
        this.executor = executor;
        this.database = database;
        this.accounts = accounts;
        this.open = new AccountUseCases.Open(lifecycle);
        this.close = new AccountUseCases.Close(lifecycle, accounts);
        this.block = new AccountUseCases.Block(lifecycle, accounts);
        this.unblock = new AccountUseCases.Unblock(lifecycle, accounts);
        this.placeHold = new AccountUseCases.PlaceHold(database, accounts);
        this.releaseHold = new AccountUseCases.ReleaseHold(database, accounts);
        this.readBalance = new AccountUseCases.ReadBalance(database, accounts);
    }

    /** Un compte s'ouvre dans l'agence de l'appelant — jamais dans celle que le corps propose. */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Requests.Created open(Caller caller, @PathVariable UUID legalEntityId,
                                 @RequestBody Requests.OpenAccount body) {
        CurrencyRef currency = database.inTransaction(
            c -> AccountUseCases.currency(c, body.currency()));
        UUID id = executor.run(caller, open, new AccountLifecycle.Opening(
            legalEntityId, body.code(), body.holderPartyId(), body.productCode(), currency,
            Callers.branchId(caller), Callers.actorId(caller), body.approverId()));
        return new Requests.Created(id);
    }

    @GetMapping("/{accountId}/balance")
    public AccountUseCases.Balance balance(Caller caller, @PathVariable UUID legalEntityId,
                                           @PathVariable UUID accountId) {
        return executor.run(caller, readBalance,
                            new AccountUseCases.BalanceQuery(accountId, caller.branchId()));
    }

    @PostMapping("/{accountId}/closure")
    public AccountLifecycle.Closure close(Caller caller, @PathVariable UUID legalEntityId,
                                          @PathVariable UUID accountId,
                                          @RequestBody Requests.CloseAccount body) {
        return executor.run(caller, close, new AccountLifecycle.Closing(
            accountId, body.payoutAccountId(), Callers.actorId(caller), body.approverId()));
    }

    @PostMapping("/{accountId}/blocks")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Requests.Created block(Caller caller, @PathVariable UUID legalEntityId,
                                  @PathVariable UUID accountId,
                                  @RequestBody Requests.BlockAccount body) {
        UUID id = executor.run(caller, block, new AccountLifecycle.Block(
            accountId, BlockKind.valueOf(body.kind()), body.reason(), body.reference(),
            Callers.actorId(caller), body.approverId()));
        return new Requests.Created(id);
    }

    @PostMapping("/{accountId}/blocks/{blockId}/lift")
    public ResponseEntity<Void> lift(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID accountId, @PathVariable UUID blockId,
                                     @RequestBody Requests.LiftBlock body) {
        executor.run(caller, unblock, new AccountUseCases.Lift(
            accountId, blockId, body.reason(), Callers.actorId(caller), body.approverId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/holds")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Requests.Created hold(Caller caller, @PathVariable UUID legalEntityId,
                                 @PathVariable UUID accountId,
                                 @RequestBody Requests.PlaceHold body) {
        Account account = accounts.require(accountId);
        LocalDate on = database.inTransaction(
            c -> AccountUseCases.businessDate(c, account.legalEntityId()));
        UUID id = executor.run(caller, placeHold, new Holds.Placement(
            accountId, new Requests.Amount(body.amount(), body.currency()).on(account),
            body.type(), body.reference(), on, body.expiresOn(), Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/{accountId}/holds/{holdId}/release")
    public ResponseEntity<Void> release(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID accountId, @PathVariable UUID holdId) {
        Account account = accounts.require(accountId);
        LocalDate on = database.inTransaction(
            c -> AccountUseCases.businessDate(c, account.legalEntityId()));
        executor.run(caller, releaseHold,
                     new AccountUseCases.Release(accountId, holdId, on, Callers.actorId(caller)));
        return ResponseEntity.noContent().build();
    }
}
