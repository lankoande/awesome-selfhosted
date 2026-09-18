package io.corebanking.api.web;

import io.corebanking.api.usecase.EstablishmentUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * L'etablissement : ce qui figure en en-tete de chaque releve et de chaque etat transmis au
 * superviseur.
 *
 * <p>Le code, le pays et la devise de tenue ne s'y modifient pas — ils sont poses dans chaque
 * ecriture depuis le premier jour. Le reste se corrige, a deux.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/establishment")
public class EstablishmentController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final EstablishmentUseCases.ReadEstablishment read;

    public EstablishmentController(UseCaseExecutor executor, Database database,
                                   MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.read = new EstablishmentUseCases.ReadEstablishment(database);
    }

    @GetMapping
    public Entities.Establishment read(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, read, legalEntityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View update(Caller caller, @PathVariable UUID legalEntityId,
                                    @RequestBody Requests.EstablishmentUpdate body) {
        return makerChecker.submit(caller, legalEntityId, "ESTABLISHMENT_UPDATE",
                                   Payloads.of("name", body.name(),
                                               "bankCode", body.bankCode(),
                                               "legalName", body.legalName(),
                                               "approvalNumber", body.approvalNumber(),
                                               "taxId", body.taxId(),
                                               "registryNumber", body.registryNumber(),
                                               "address", body.address(),
                                               "phone", body.phone(),
                                               "email", body.email()));
    }
}
