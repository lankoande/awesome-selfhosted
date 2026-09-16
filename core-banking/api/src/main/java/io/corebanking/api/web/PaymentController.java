package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.Paging;
import io.corebanking.api.usecase.PaymentUseCases;
import io.corebanking.deposits.PaymentService;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paiements sortants : l'ordre debite le client et attend le correspondant ; le back-office
 * l'envoie, le regle sur le nostro, le retourne, ou l'annule avant envoi. Un ordre rejoue avec
 * la meme cle repond 200 avec l'ordre existant ; un ordre nouveau 201.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class PaymentController {

    private final UseCaseExecutor executor;
    private final AccountDirectory accounts;
    private final PaymentUseCases.Order order;
    private final PaymentUseCases.Process process;
    private final PaymentUseCases.Read read;
    private final PaymentUseCases.List_ list;

    public PaymentController(UseCaseExecutor executor, PaymentService payments, Database database,
                             AccountDirectory accounts) {
        this.executor = executor;
        this.accounts = accounts;
        this.order = new PaymentUseCases.Order(payments);
        this.process = new PaymentUseCases.Process(payments, database);
        this.read = new PaymentUseCases.Read(database);
        this.list = new PaymentUseCases.List_(database);
    }

    @PostMapping("/accounts/{accountId}/payment-orders")
    public ResponseEntity<PaymentService.PaymentOrder> order(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId,
            IdempotencyKey key, @RequestBody Requests.PaymentOrderRequest body) {
        Account account = accounts.require(accountId);
        PaymentService.Placed placed = executor.run(caller, order, new PaymentService.Order(
            key, legalEntityId, accountId,
            new Requests.Amount(body.amount(), body.currency()).on(account),
            body.beneficiaryName(), body.beneficiaryBank(), body.beneficiaryAccount(),
            body.reference(), body.channel(), Callers.actorId(caller)));
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(placed.order());
    }

    @GetMapping("/payment-orders/{orderId}")
    public PaymentService.PaymentOrder read(Caller caller, @PathVariable UUID legalEntityId,
                                            @PathVariable UUID orderId) {
        return executor.run(caller, read, new PaymentUseCases.Lookup(legalEntityId, orderId));
    }

    @GetMapping("/payment-orders")
    public Paging.Paged<PaymentService.PaymentOrder> list(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String status, Paging.PageRequest page) {
        return executor.run(caller, list, new PaymentUseCases.Query(legalEntityId, status, page));
    }

    @PostMapping("/payment-orders/{orderId}/send")
    public PaymentService.PaymentOrder send(Caller caller, @PathVariable UUID legalEntityId,
                                            @PathVariable UUID orderId) {
        return executor.run(caller, process, new PaymentUseCases.Step(
            legalEntityId, orderId, PaymentUseCases.Transition.SEND, null, null,
            Callers.actorId(caller)));
    }

    @PostMapping("/payment-orders/{orderId}/settlement")
    public PaymentService.PaymentOrder settle(Caller caller, @PathVariable UUID legalEntityId,
                                              @PathVariable UUID orderId,
                                              @RequestBody Requests.Settlement body) {
        return executor.run(caller, process, new PaymentUseCases.Step(
            legalEntityId, orderId, PaymentUseCases.Transition.SETTLE, body.nostroAccountId(),
            null, Callers.actorId(caller)));
    }

    @PostMapping("/payment-orders/{orderId}/return")
    public PaymentService.PaymentOrder returnOrder(Caller caller, @PathVariable UUID legalEntityId,
                                                   @PathVariable UUID orderId,
                                                   @RequestBody Requests.Reason body) {
        return executor.run(caller, process, new PaymentUseCases.Step(
            legalEntityId, orderId, PaymentUseCases.Transition.RETURN, null, body.reason(),
            Callers.actorId(caller)));
    }

    @PostMapping("/payment-orders/{orderId}/cancellation")
    public PaymentService.PaymentOrder cancel(Caller caller, @PathVariable UUID legalEntityId,
                                              @PathVariable UUID orderId,
                                              @RequestBody Requests.Reason body) {
        return executor.run(caller, process, new PaymentUseCases.Step(
            legalEntityId, orderId, PaymentUseCases.Transition.CANCEL, null, body.reason(),
            Callers.actorId(caller)));
    }
}
