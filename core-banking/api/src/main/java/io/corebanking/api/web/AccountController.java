package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.api.usecase.Paging;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ouverture, consultation, blocages, blocages de montant, cloture. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/accounts")
public class AccountController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final AccountUseCases.SearchAccounts search;
    private final AccountUseCases.ReadChart chart;
    private final AccountUseCases.ReadBalance readBalance;
    private final AccountUseCases.ReadJournal readJournal;
    private final AccountUseCases.ReadLedger readLedger;
    private final io.corebanking.api.usecase.PaymentUseCases.ReadLimits readLimits;

    public AccountController(UseCaseExecutor executor, Database database,
                             AccountDirectory accounts, MakerChecker makerChecker) {
        this.executor = executor;
        this.search = new AccountUseCases.SearchAccounts(database);
        this.chart = new AccountUseCases.ReadChart(database);
        this.makerChecker = makerChecker;
        this.readBalance = new AccountUseCases.ReadBalance(database, accounts);
        this.readJournal = new AccountUseCases.ReadJournal(database, accounts);
        this.readLedger = new AccountUseCases.ReadLedger(database, accounts);
        this.readLimits = new io.corebanking.api.usecase.PaymentUseCases.ReadLimits(database, accounts);
    }

    /**
     * Un compte s'ouvre a deux : la requete est soumise, et un second porteur l'approuve. Elle
     * s'ouvrira dans l'agence du maker — jamais dans celle que le corps proposerait.
     */
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View open(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.OpenAccount body) {
        return makerChecker.submit(caller, legalEntityId, "ACCOUNT_OPEN", Map.of(
            "code", nz(body.code()), "holderPartyId", nz(body.holderPartyId()),
            "productCode", nz(body.productCode()), "currency", nz(body.currency())));
    }

    /**
     * Les comptes clients de l'entite, par pages : tous, ceux d'un titulaire, ceux d'une agence,
     * ou ceux dont le numero, le nom du titulaire ou sa reference contient {@code q}.
     */
    @GetMapping
    public Paging.Paged<Accounts.Summary> list(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String q, Paging.PageRequest page) {
        return executor.run(caller, search, new AccountUseCases.AccountQuery(
            legalEntityId, partyId, branchId, q, page));
    }

    /**
     * Le plan comptable : les comptes qu'un parametrage peut designer.
     *
     * <p>Distinct de la liste ci-dessus, qui rend les comptes <b>clients</b>. Ici, tout ce qui
     * n'est pas un compte client : general, interne, nostro, suspens, position. Sans solde — un
     * compte d'imputation se choisit sur ce qu'il est, pas sur ce qu'il porte.
     */
    @GetMapping("/general")
    public List<Accounts.General> general(Caller caller, @PathVariable UUID legalEntityId,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit) {
        return executor.run(caller, chart, new AccountUseCases.ChartQuery(
            legalEntityId, q, limit == null ? 50 : limit));
    }

    @GetMapping("/{accountId}/balance")
    public AccountUseCases.Balance balance(Caller caller, @PathVariable UUID legalEntityId,
                                           @PathVariable UUID accountId) {
        return executor.run(caller, readBalance,
                            new AccountUseCases.BalanceQuery(accountId, caller.branchId()));
    }

    /** Le releve : les mouvements du compte sur une plage de dates comptables, par pages. */
    @GetMapping("/{accountId}/journal")
    public io.corebanking.api.usecase.Paging.Paged<io.corebanking.ledger.store.Journal.StatementLine>
            journal(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId,
                    @org.springframework.web.bind.annotation.RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                        iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate from,
                    @org.springframework.web.bind.annotation.RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                        iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate to,
                    io.corebanking.api.usecase.Paging.PageRequest page) {
        return executor.run(caller, readJournal,
                            new AccountUseCases.JournalQuery(accountId, from, to, page));
    }

    /** Le grand livre du compte : les memes mouvements, par curseur, pour les longues plages. */
    @GetMapping("/{accountId}/ledger")
    public io.corebanking.api.usecase.Paging.Slice<io.corebanking.ledger.store.Journal.StatementLine>
            ledger(Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId,
                   @org.springframework.web.bind.annotation.RequestParam(required = false)
                   @org.springframework.format.annotation.DateTimeFormat(
                       iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                   java.time.LocalDate from,
                   @org.springframework.web.bind.annotation.RequestParam(required = false)
                   @org.springframework.format.annotation.DateTimeFormat(
                       iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                   java.time.LocalDate to,
                   io.corebanking.api.usecase.Paging.CursorRequest cursor) {
        return executor.run(caller, readLedger,
                            new AccountUseCases.LedgerQuery(accountId, from, to, cursor));
    }

    /** Un plafond propre au compte, a deux : demande par l'un, valide par un autre de l'agence. */
    @PostMapping("/{accountId}/limits")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View limit(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID accountId,
                                   @RequestBody Requests.AccountLimitRequest body) {
        if (body.validFrom() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
        }
        return makerChecker.submit(caller, legalEntityId, "ACCOUNT_LIMIT_SET", Payloads.of(
            "accountId", accountId, "kind", nz(body.kind()), "amount", nz(body.amount()),
            "currency", nz(body.currency()), "validFrom", body.validFrom(),
            "validTo", body.validTo()));
    }

    @GetMapping("/{accountId}/limits")
    public java.util.List<io.corebanking.deposits.Limits.AccountLimit> limits(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID accountId) {
        return executor.run(caller, readLimits,
                            new io.corebanking.api.usecase.PaymentUseCases.LimitsQuery(accountId));
    }

    @PostMapping("/{accountId}/closure")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View close(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID accountId,
                                   @RequestBody Requests.CloseAccount body) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("accountId", accountId.toString());
        if (body.payoutAccountId() != null) {
            payload.put("payoutAccountId", body.payoutAccountId().toString());
        }
        return makerChecker.submit(caller, legalEntityId, "ACCOUNT_CLOSE", payload);
    }

    @PostMapping("/{accountId}/blocks")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View block(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID accountId,
                                   @RequestBody Requests.BlockAccount body) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("accountId", accountId.toString());
        payload.put("kind", nz(body.kind()));
        payload.put("reason", nz(body.reason()));
        if (body.reference() != null) {
            payload.put("reference", body.reference());
        }
        return makerChecker.submit(caller, legalEntityId, "ACCOUNT_BLOCK", payload);
    }

    @PostMapping("/{accountId}/blocks/{blockId}/lift")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View lift(Caller caller, @PathVariable UUID legalEntityId,
                                  @PathVariable UUID accountId, @PathVariable UUID blockId,
                                  @RequestBody Requests.LiftBlock body) {
        return makerChecker.submit(caller, legalEntityId, "ACCOUNT_UNBLOCK", Map.of(
            "accountId", accountId.toString(), "blockId", blockId.toString(),
            "reason", nz(body.reason())));
    }

    @PostMapping("/{accountId}/holds")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View hold(Caller caller, @PathVariable UUID legalEntityId,
                                  @PathVariable UUID accountId,
                                  @RequestBody Requests.PlaceHold body) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("accountId", accountId.toString());
        payload.put("amount", nz(body.amount()));
        payload.put("currency", nz(body.currency()));
        payload.put("type", nz(body.type()));
        if (body.reference() != null) {
            payload.put("reference", body.reference());
        }
        if (body.expiresOn() != null) {
            payload.put("expiresOn", body.expiresOn().toString());
        }
        return makerChecker.submit(caller, legalEntityId, "HOLD_PLACE", payload);
    }

    @PostMapping("/{accountId}/holds/{holdId}/release")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View release(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID accountId, @PathVariable UUID holdId) {
        return makerChecker.submit(caller, legalEntityId, "HOLD_RELEASE", Map.of(
            "accountId", accountId.toString(), "holdId", holdId.toString()));
    }

    private static Object nz(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent");
        }
        return value instanceof UUID ? value.toString() : value;
    }
}
