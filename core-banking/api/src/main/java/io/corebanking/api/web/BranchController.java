package io.corebanking.api.web;

import io.corebanking.api.usecase.NetworkUseCases;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reseau : une agence ou une region se cree a deux, avec ses comptes de liaison par devise. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/branches")
public class BranchController {

    private final MakerChecker makerChecker;
    private final UseCaseExecutor executor;
    private final NetworkUseCases.ListBranches branches;

    public BranchController(MakerChecker makerChecker, UseCaseExecutor executor,
                            Database database) {
        this.makerChecker = makerChecker;
        this.executor = executor;
        this.branches = new NetworkUseCases.ListBranches(database);
    }

    /**
     * Le reseau de l'entite, siege compris.
     *
     * <p>Sans cette lecture, une agence creee n'apparaissait nulle part : ni dans un ecran de
     * parametrage, ni dans un filtre, ni pour verifier qu'elle avait bien ete creee.
     */
    @GetMapping
    public List<Branches.Branch> list(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, branches, new NetworkUseCases.Query(legalEntityId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View create(Caller caller, @PathVariable UUID legalEntityId,
                                    @RequestBody Requests.CreateBranch body) {
        Map<String, Object> liaison = new LinkedHashMap<>();
        if (body.liaisonAccounts() != null) {
            body.liaisonAccounts().forEach((currency, account) ->
                liaison.put(currency, String.valueOf(account)));
        }
        return makerChecker.submit(caller, legalEntityId, "BRANCH_CREATE", Payloads.of(
            "code", body.code(), "name", body.name(), "kind", body.kind(),
            "parentId", body.parentId(), "openedOn", body.openedOn(),
            "liaisonAccounts", liaison));
    }
}
