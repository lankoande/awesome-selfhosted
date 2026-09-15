package io.corebanking.api.web;

import io.corebanking.security.Caller;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    public BranchController(MakerChecker makerChecker) {
        this.makerChecker = makerChecker;
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
