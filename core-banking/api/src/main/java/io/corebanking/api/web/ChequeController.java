package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.ChequeUseCases;
import io.corebanking.api.usecase.Paging;
import io.corebanking.api.usecase.TillUseCases;
import io.corebanking.deposits.ChequeService;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cheques : le chequier se delivre a deux ; un cheque emis se paie au guichet — sur la caisse de
 * l'appelant — ou par compensation sur un nostro, une seule fois ; l'opposition l'arrete ; une
 * remise credite le client sauf bonne fin, et le back-office la regle ou la retourne impayee.
 * Un paiement ou une remise rejoue avec la meme cle repond 200 avec l'objet existant, un nouveau
 * 201. Un cheque sans provision est rejete en 409, et l'incident est enregistre.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class ChequeController {

    private final UseCaseExecutor executor;
    private final Database database;
    private final AccountDirectory accounts;
    private final MakerChecker makerChecker;
    private final ChequeUseCases.Pay pay;
    private final ChequeUseCases.StopCheque stop;
    private final ChequeUseCases.Deposit deposit;
    private final ChequeUseCases.Process process;
    private final ChequeUseCases.ReadBooks readBooks;
    private final ChequeUseCases.ReadCheques readCheques;
    private final ChequeUseCases.ReadIncidents readIncidents;
    private final ChequeUseCases.ReadDeposit readDeposit;
    private final ChequeUseCases.ListDeposits listDeposits;

    public ChequeController(UseCaseExecutor executor, ChequeService cheques, Database database,
                            AccountDirectory accounts, MakerChecker makerChecker) {
        this.executor = executor;
        this.database = database;
        this.accounts = accounts;
        this.makerChecker = makerChecker;
        this.pay = new ChequeUseCases.Pay(cheques, accounts);
        this.stop = new ChequeUseCases.StopCheque(cheques);
        this.deposit = new ChequeUseCases.Deposit(cheques);
        this.process = new ChequeUseCases.Process(cheques, database);
        this.readBooks = new ChequeUseCases.ReadBooks(database, accounts);
        this.readCheques = new ChequeUseCases.ReadCheques(database, accounts);
        this.readIncidents = new ChequeUseCases.ReadIncidents(database, accounts);
        this.readDeposit = new ChequeUseCases.ReadDeposit(database);
        this.listDeposits = new ChequeUseCases.ListDeposits(database);
    }

    // ------------------------------------------------------------------ chequiers

    /** Un chequier, a deux : demande par l'un, valide par un autre de l'agence du compte. */
    @PostMapping("/accounts/{accountId}/cheque-books")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View issueBook(Caller caller, @PathVariable UUID legalEntityId,
                                       @PathVariable UUID accountId,
                                       @RequestBody Requests.ChequeBookRequest body) {
        if (body.count() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : count");
        }
        return makerChecker.submit(caller, legalEntityId, "CHEQUE_BOOK_ISSUE", Payloads.of(
            "accountId", accountId, "count", String.valueOf(body.count())));
    }

    @GetMapping("/accounts/{accountId}/cheque-books")
    public List<ChequeService.Book> books(Caller caller, @PathVariable UUID legalEntityId,
                                          @PathVariable UUID accountId) {
        return executor.run(caller, readBooks, new ChequeUseCases.AccountQuery(accountId, null));
    }

    @GetMapping("/accounts/{accountId}/cheques")
    public List<ChequeService.Cheque> cheques(Caller caller, @PathVariable UUID legalEntityId,
                                              @PathVariable UUID accountId,
                                              @RequestParam(required = false) String status) {
        return executor.run(caller, readCheques, new ChequeUseCases.AccountQuery(accountId, status));
    }

    @GetMapping("/accounts/{accountId}/cheque-incidents")
    public List<ChequeService.Incident> incidents(Caller caller, @PathVariable UUID legalEntityId,
                                                  @PathVariable UUID accountId) {
        return executor.run(caller, readIncidents,
                            new ChequeUseCases.AccountQuery(accountId, null));
    }

    // ------------------------------------------------------------------ paiement et opposition

    /**
     * Paiement d'un cheque emis : au guichet ({@code CASH}, par defaut) sur la caisse de
     * l'appelant ; par compensation ({@code CLEARING}) sur le nostro donne.
     */
    @PostMapping("/accounts/{accountId}/cheques/{number}/payment")
    public ResponseEntity<ChequeService.Paid> pay(Caller caller, @PathVariable UUID legalEntityId,
                                                  @PathVariable UUID accountId,
                                                  @PathVariable long number, IdempotencyKey key,
                                                  @RequestBody Requests.ChequePaymentRequest body) {
        Account account = accounts.require(accountId);
        ChequeService.PaymentMode mode = mode(body.mode());
        UUID counterparty;
        if (mode == ChequeService.PaymentMode.CASH) {
            counterparty = TillUseCases.ofCaller(database, caller).cashAccountId();
        } else {
            if (body.nostroAccountId() == null) {
                throw new IllegalArgumentException(
                    "Champ obligatoire absent : nostroAccountId (paiement par compensation)");
            }
            counterparty = body.nostroAccountId();
        }
        ChequeService.Paid paid = executor.run(caller, pay, new ChequeService.Payment(
            key, legalEntityId, accountId, number,
            new Requests.Amount(body.amount(), body.currency()).on(account), mode, counterparty,
            body.beneficiary(), body.channel(), Callers.actorId(caller)));
        return ResponseEntity.status(paid.receipt().replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(paid);
    }

    @PostMapping("/accounts/{accountId}/cheques/{number}/stop")
    public ChequeService.Cheque stop(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID accountId, @PathVariable long number,
                                     @RequestBody Requests.ChequeStop body) {
        return executor.run(caller, stop, new ChequeUseCases.Stop(
            legalEntityId, accountId, number, stopReason(body.reason()), Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ remises

    @PostMapping("/accounts/{accountId}/cheque-deposits")
    public ResponseEntity<ChequeService.ChequeDeposit> deposit(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId,
            IdempotencyKey key, @RequestBody Requests.ChequeDepositRequest body) {
        Account account = accounts.require(accountId);
        ChequeService.Deposited deposited = executor.run(caller, deposit, new ChequeService.Deposit(
            key, legalEntityId, accountId,
            new Requests.Amount(body.amount(), body.currency()).on(account), body.draweeBank(),
            body.chequeNumber(), body.drawerName(), body.channel(), Callers.actorId(caller)));
        return ResponseEntity.status(deposited.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(deposited.deposit());
    }

    @GetMapping("/cheque-deposits/{depositId}")
    public ChequeService.ChequeDeposit readDeposit(Caller caller, @PathVariable UUID legalEntityId,
                                                   @PathVariable UUID depositId) {
        return executor.run(caller, readDeposit,
                            new ChequeUseCases.DepositLookup(legalEntityId, depositId));
    }

    @GetMapping("/cheque-deposits")
    public Paging.Paged<ChequeService.ChequeDeposit> deposits(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String status, Paging.PageRequest page) {
        return executor.run(caller, listDeposits,
                            new ChequeUseCases.DepositQuery(legalEntityId, status, page));
    }

    @PostMapping("/cheque-deposits/{depositId}/settlement")
    public ChequeService.ChequeDeposit settle(Caller caller, @PathVariable UUID legalEntityId,
                                              @PathVariable UUID depositId,
                                              @RequestBody Requests.Settlement body) {
        return executor.run(caller, process, new ChequeUseCases.Step(
            legalEntityId, depositId, ChequeUseCases.Transition.SETTLE, body.nostroAccountId(),
            null, Callers.actorId(caller)));
    }

    @PostMapping("/cheque-deposits/{depositId}/return")
    public ChequeService.ChequeDeposit returnDeposit(Caller caller,
                                                     @PathVariable UUID legalEntityId,
                                                     @PathVariable UUID depositId,
                                                     @RequestBody Requests.Reason body) {
        return executor.run(caller, process, new ChequeUseCases.Step(
            legalEntityId, depositId, ChequeUseCases.Transition.RETURN, null, body.reason(),
            Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ interne

    private static ChequeService.PaymentMode mode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ChequeService.PaymentMode.CASH;
        }
        try {
            return ChequeService.PaymentMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mode de paiement inconnu : " + mode
                                               + " (CASH ou CLEARING)");
        }
    }

    private static ChequeService.StopReason stopReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : reason");
        }
        try {
            return ChequeService.StopReason.valueOf(reason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Motif d'opposition inconnu : " + reason
                + " (LOSS, THEFT, FRAUDULENT_USE, BEARER_INSOLVENCY)");
        }
    }
}
