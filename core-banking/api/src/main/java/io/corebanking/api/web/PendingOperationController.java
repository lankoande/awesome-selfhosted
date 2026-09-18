package io.corebanking.api.web;

import io.corebanking.security.Caller;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Les operations en attente de double validation : lecture, approbation, rejet. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/pending-operations")
public class PendingOperationController {

    private final MakerChecker makerChecker;

    public PendingOperationController(MakerChecker makerChecker) {
        this.makerChecker = makerChecker;
    }

    @GetMapping
    public io.corebanking.api.usecase.Paging.Paged<MakerChecker.View> pending(
            Caller caller, @PathVariable UUID legalEntityId,
            io.corebanking.api.usecase.Paging.PageRequest page) {
        return makerChecker.pending(caller, page);
    }

    @GetMapping("/{id}")
    public MakerChecker.View read(Caller caller, @PathVariable UUID legalEntityId,
                                  @PathVariable UUID id) {
        return makerChecker.read(caller, id);
    }

    /** Le checker approuve : l'operation s'execute, avec lui pour approbateur. */
    @PostMapping("/{id}/approve")
    public MakerChecker.View approve(Caller caller, @PathVariable UUID legalEntityId,
                                     @PathVariable UUID id) {
        return makerChecker.approve(caller, id);
    }

    @PostMapping("/{id}/reject")
    public MakerChecker.View reject(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable UUID id,
                                    @RequestBody Map<String, Object> body) {
        Object reason = body.get("reason");
        return makerChecker.reject(caller, id, reason == null ? null : reason.toString());
    }
}
