package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
    private final MakerChecker makerChecker;
    private final AccountUseCases.ReadBalance readBalance;

    public AccountController(UseCaseExecutor executor, Database database,
                             AccountDirectory accounts, MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readBalance = new AccountUseCases.ReadBalance(database, accounts);
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

    @GetMapping("/{accountId}/balance")
    public AccountUseCases.Balance balance(Caller caller, @PathVariable UUID legalEntityId,
                                           @PathVariable UUID accountId) {
        return executor.run(caller, readBalance,
                            new AccountUseCases.BalanceQuery(accountId, caller.branchId()));
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
