package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.DirectDebitUseCases;
import io.corebanking.api.usecase.Paging;
import io.corebanking.deposits.DirectDebitService;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
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
 * Prelevements : le mandat s'enregistre a deux et se revoque ; un prelevement recu est presente
 * sur un mandat et s'execute a l'echeance — tout de suite si elle est arrivee, par l'arrete sinon
 * — ou est rejete avec son motif ; un prelevement emis credite le creancier sauf bonne fin a
 * l'echeance ; le back-office regle, rappelle, rembourse, retourne. Une presentation ou une
 * remise rejouee avec la meme cle repond 200 avec l'objet existant, une nouvelle 201 — un rejet
 * est un resultat, rendu avec son motif, pas une erreur.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class DirectDebitController {

    private final UseCaseExecutor executor;
    private final Database database;
    private final AccountDirectory accounts;
    private final MakerChecker makerChecker;
    private final DirectDebitUseCases.Revoke revoke;
    private final DirectDebitUseCases.Present presentFromClearing;
    private final DirectDebitUseCases.Present presentForCreditor;
    private final DirectDebitUseCases.Issue issue;
    private final DirectDebitUseCases.Process process;
    private final DirectDebitUseCases.ReadMandates readMandates;
    private final DirectDebitUseCases.ReadAccountDebits readAccountDebits;
    private final DirectDebitUseCases.Read read;
    private final DirectDebitUseCases.List_ list;

    public DirectDebitController(UseCaseExecutor executor, DirectDebitService directDebits,
                                 Database database, AccountDirectory accounts,
                                 MakerChecker makerChecker) {
        this.executor = executor;
        this.database = database;
        this.accounts = accounts;
        this.makerChecker = makerChecker;
        this.revoke = new DirectDebitUseCases.Revoke(directDebits);
        this.presentFromClearing = new DirectDebitUseCases.Present(directDebits, false);
        this.presentForCreditor = new DirectDebitUseCases.Present(directDebits, true);
        this.issue = new DirectDebitUseCases.Issue(directDebits);
        this.process = new DirectDebitUseCases.Process(directDebits, database);
        this.readMandates = new DirectDebitUseCases.ReadMandates(database, accounts);
        this.readAccountDebits = new DirectDebitUseCases.ReadAccountDebits(database, accounts);
        this.read = new DirectDebitUseCases.Read(database);
        this.list = new DirectDebitUseCases.List_(database);
    }

    // ------------------------------------------------------------------ mandats

    /** Un mandat, a deux : demande par l'un, valide par un autre de l'agence du compte. */
    @PostMapping("/accounts/{accountId}/mandates")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View register(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID accountId,
                                      @RequestBody Requests.MandateRequest body) {
        if (body.signedOn() == null || body.validFrom() == null) {
            throw new IllegalArgumentException("Champs obligatoires absents : signedOn, validFrom");
        }
        return makerChecker.submit(caller, legalEntityId, "MANDATE_REGISTER", Payloads.of(
            "accountId", accountId, "reference", body.reference(), "creditorId", body.creditorId(),
            "creditorName", body.creditorName(), "creditorAccountId", body.creditorAccountId(),
            "creditorBank", body.creditorBank(), "creditorAccount", body.creditorAccount(),
            "signedOn", body.signedOn(), "validFrom", body.validFrom(), "validTo", body.validTo(),
            "maxAmount", body.maxAmount(), "currency", body.currency()));
    }

    @GetMapping("/accounts/{accountId}/mandates")
    public List<DirectDebitService.Mandate> mandates(Caller caller, @PathVariable UUID legalEntityId,
                                                     @PathVariable UUID accountId) {
        return executor.run(caller, readMandates, new DirectDebitUseCases.AccountQuery(accountId));
    }

    @PostMapping("/mandates/{mandateId}/revocation")
    public DirectDebitService.Mandate revoke(Caller caller, @PathVariable UUID legalEntityId,
                                             @PathVariable UUID mandateId,
                                             @RequestBody Requests.Reason body) {
        return executor.run(caller, revoke, new DirectDebitUseCases.Revocation(
            legalEntityId, mandateId, body.reason(), Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ presentation et remise

    /**
     * Un prelevement recu, presente sur le mandat ; execute tout de suite si l'echeance est
     * arrivee. Sur un creancier d'ailleurs, c'est la compensation qui presente ; sur un creancier
     * de la banque, c'est sa remise, plafonnee par role.
     */
    @PostMapping("/mandates/{mandateId}/direct-debits")
    public ResponseEntity<DirectDebitService.DirectDebit> present(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID mandateId,
            IdempotencyKey key, @RequestBody Requests.DirectDebitPresentation body) {
        DirectDebitService.Mandate mandate = database.inTransaction(
                c -> DirectDebitService.findMandate(c, mandateId))
            .filter(m -> m.legalEntityId().equals(legalEntityId))
            .orElseThrow(() -> new DirectDebitService.UnknownMandateException(mandateId));
        Account debtor = accounts.require(mandate.accountId());
        DirectDebitService.Presented presented = executor.run(caller,
            mandate.internal() ? presentForCreditor : presentFromClearing,
            new DirectDebitService.Presentation(key, legalEntityId, mandateId,
                new Requests.Amount(body.amount(), body.currency()).on(debtor), body.dueDate(),
                body.reference(), body.channel(), Callers.actorId(caller)));
        return respond(presented);
    }

    /** Une remise de prelevement emis par le creancier titulaire du compte. */
    @PostMapping("/accounts/{accountId}/issued-direct-debits")
    public ResponseEntity<DirectDebitService.DirectDebit> issue(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId,
            IdempotencyKey key, @RequestBody Requests.DirectDebitIssue body) {
        Account creditor = accounts.require(accountId);
        DirectDebitService.Presented presented = executor.run(caller, issue,
            new DirectDebitService.Issue(key, legalEntityId, accountId,
                new Requests.Amount(body.amount(), body.currency()).on(creditor), body.dueDate(),
                body.debtorName(), body.debtorBank(), body.debtorAccount(), body.mandateReference(),
                body.reference(), body.channel(), Callers.actorId(caller)));
        return respond(presented);
    }

    private static ResponseEntity<DirectDebitService.DirectDebit> respond(
            DirectDebitService.Presented presented) {
        return ResponseEntity.status(presented.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(presented.directDebit());
    }

    // ------------------------------------------------------------------ suivi et lecture

    @GetMapping("/accounts/{accountId}/direct-debits")
    public List<DirectDebitService.DirectDebit> ofAccount(Caller caller,
                                                          @PathVariable UUID legalEntityId,
                                                          @PathVariable UUID accountId) {
        return executor.run(caller, readAccountDebits,
                            new DirectDebitUseCases.AccountQuery(accountId));
    }

    @GetMapping("/direct-debits/{directDebitId}")
    public DirectDebitService.DirectDebit read(Caller caller, @PathVariable UUID legalEntityId,
                                               @PathVariable UUID directDebitId) {
        return executor.run(caller, read, new DirectDebitUseCases.Lookup(legalEntityId, directDebitId));
    }

    @GetMapping("/direct-debits")
    public Paging.Paged<DirectDebitService.DirectDebit> list(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status, Paging.PageRequest page) {
        return executor.run(caller, list,
                            new DirectDebitUseCases.Query(legalEntityId, direction, status, page));
    }

    @PostMapping("/direct-debits/{directDebitId}/settlement")
    public DirectDebitService.DirectDebit settle(Caller caller, @PathVariable UUID legalEntityId,
                                                 @PathVariable UUID directDebitId,
                                                 @RequestBody Requests.Settlement body) {
        return executor.run(caller, process, new DirectDebitUseCases.Step(
            legalEntityId, directDebitId, DirectDebitUseCases.Transition.SETTLE,
            body.nostroAccountId(), null, Callers.actorId(caller)));
    }

    @PostMapping("/direct-debits/{directDebitId}/cancellation")
    public DirectDebitService.DirectDebit cancel(Caller caller, @PathVariable UUID legalEntityId,
                                                 @PathVariable UUID directDebitId,
                                                 @RequestBody Requests.Reason body) {
        return step(caller, legalEntityId, directDebitId, DirectDebitUseCases.Transition.CANCEL, body);
    }

    @PostMapping("/direct-debits/{directDebitId}/refund")
    public DirectDebitService.DirectDebit refund(Caller caller, @PathVariable UUID legalEntityId,
                                                 @PathVariable UUID directDebitId,
                                                 @RequestBody Requests.Reason body) {
        return step(caller, legalEntityId, directDebitId, DirectDebitUseCases.Transition.REFUND, body);
    }

    @PostMapping("/direct-debits/{directDebitId}/return")
    public DirectDebitService.DirectDebit returnIssued(Caller caller,
                                                       @PathVariable UUID legalEntityId,
                                                       @PathVariable UUID directDebitId,
                                                       @RequestBody Requests.Reason body) {
        return step(caller, legalEntityId, directDebitId, DirectDebitUseCases.Transition.RETURN, body);
    }

    private DirectDebitService.DirectDebit step(Caller caller, UUID legalEntityId, UUID id,
                                                DirectDebitUseCases.Transition transition,
                                                Requests.Reason body) {
        return executor.run(caller, process, new DirectDebitUseCases.Step(
            legalEntityId, id, transition, null, body.reason(), Callers.actorId(caller)));
    }
}
