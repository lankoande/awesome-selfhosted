package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.OperationUseCases;
import io.corebanking.deposits.OperationsService;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versements, retraits, virements.
 *
 * <p>Le contrat de chaque methode est celui qui a ete valide : l'appelant vient du jeton, la cle
 * d'idempotence de l'en-tete, le compte du chemin, le reste du corps ; tout passe par
 * {@link UseCaseExecutor}, seul point ou l'habilitation s'applique. Une operation rejouee avec la
 * meme cle repond 200 avec son premier resultat ; une operation nouvelle repond 201. La caisse
 * d'une operation de guichet est celle de l'appelant, resolue depuis son jeton — jamais choisie
 * dans la requete.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class OperationController {

    private final UseCaseExecutor executor;
    private final AccountDirectory accounts;
    private final OperationUseCases.Deposit deposit;
    private final OperationUseCases.Withdraw withdraw;
    private final OperationUseCases.Transfer transfer;

    private final io.corebanking.ledger.store.Database database;

    public OperationController(UseCaseExecutor executor, OperationsService operations,
                               AccountDirectory accounts,
                               io.corebanking.ledger.store.Database database) {
        this.executor = executor;
        this.accounts = accounts;
        this.database = database;
        this.deposit = new OperationUseCases.Deposit(operations, accounts);
        this.withdraw = new OperationUseCases.Withdraw(operations, accounts);
        this.transfer = new OperationUseCases.Transfer(operations);
    }

    @PostMapping("/accounts/{accountId}/deposits")
    public ResponseEntity<OperationsService.Receipt> deposit(Caller caller,
                                                             @PathVariable UUID legalEntityId,
                                                             @PathVariable UUID accountId,
                                                             IdempotencyKey key,
                                                             @RequestBody Requests.CashOperation body) {
        Account account = accounts.require(accountId);
        UUID cash = io.corebanking.api.usecase.TillUseCases.ofCaller(database, caller)
            .cashAccountId();
        OperationsService.Receipt receipt = executor.run(caller, deposit,
            new OperationsService.Deposit(key, legalEntityId, accountId, cash,
                                          body.on(account), body.channel(), body.narrative(),
                                          Callers.actorId(caller)));
        return respond(receipt);
    }

    @PostMapping("/accounts/{accountId}/withdrawals")
    public ResponseEntity<OperationsService.Receipt> withdraw(Caller caller,
                                                              @PathVariable UUID legalEntityId,
                                                              @PathVariable UUID accountId,
                                                              IdempotencyKey key,
                                                              @RequestBody Requests.CashOperation body) {
        Account account = accounts.require(accountId);
        UUID cash = io.corebanking.api.usecase.TillUseCases.ofCaller(database, caller)
            .cashAccountId();
        OperationsService.Receipt receipt = executor.run(caller, withdraw,
            new OperationsService.Withdrawal(key, legalEntityId, accountId, cash,
                                             body.on(account), body.channel(), body.narrative(),
                                             Callers.actorId(caller)));
        return respond(receipt);
    }

    @PostMapping("/transfers")
    public ResponseEntity<OperationsService.Receipt> transfer(Caller caller,
                                                              @PathVariable UUID legalEntityId,
                                                              IdempotencyKey key,
                                                              @RequestBody Requests.Transfer body) {
        Account source = accounts.require(body.sourceAccountId());
        OperationsService.Receipt receipt = executor.run(caller, transfer,
            new OperationsService.Transfer(key, legalEntityId, body.sourceAccountId(),
                                           body.destinationAccountId(),
                                           new Requests.Amount(body.amount(), body.currency())
                                               .on(source),
                                           body.channel(), body.narrative(),
                                           Callers.actorId(caller)));
        return respond(receipt);
    }

    private static ResponseEntity<OperationsService.Receipt> respond(OperationsService.Receipt receipt) {
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(receipt);
    }
}
