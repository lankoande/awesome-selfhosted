package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.TillUseCases;
import io.corebanking.deposits.TillService;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Caisses : creation a deux, arrete de caisse par son titulaire ou par le chef d'agence. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/tills")
public class TillController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final TillUseCases.Close close;

    public TillController(UseCaseExecutor executor, Database database, AccountDirectory accounts,
                          TillService tills, MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.close = new TillUseCases.Close(database, accounts, tills);
    }

    /** Une caisse affecte un compte de la banque a une personne : demandee par un chef d'agence, validee par un autre. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View create(Caller caller, @PathVariable UUID legalEntityId,
                                    @RequestBody Requests.CreateTill body) {
        return makerChecker.submit(caller, legalEntityId, "TILL_CREATE", Payloads.of(
            "code", body.code(), "cashAccountId", body.cashAccountId(),
            "tellerSubjectId", body.tellerSubjectId(),
            "differenceAccountId", body.differenceAccountId()));
    }

    /** Le comptage des especes ; l'ecart est comptabilise, la journee de caisse close. */
    @PostMapping("/{tillId}/closure")
    @ResponseStatus(HttpStatus.CREATED)
    public TillService.Closure close(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID tillId,
                                     @RequestBody Requests.TillClosing body) {
        return executor.run(caller, close, new TillUseCases.Closing(
            tillId, body.counted(), body.currency(), Callers.actorId(caller)));
    }
}
