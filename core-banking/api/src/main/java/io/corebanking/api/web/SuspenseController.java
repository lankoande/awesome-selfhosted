package io.corebanking.api.web;

import io.corebanking.api.usecase.SuspenseUseCases;
import io.corebanking.deposits.Suspense;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
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
 * Suspens : ce qui attend le correspondant — ordres non regles, remises non encaissees,
 * prelevements non regles, comptes d'attente non soldes — avec son anciennete en jours ouvres
 * et le responsable que la politique lui donne. La politique se fixe a deux, par nature.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}")
public class SuspenseController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final SuspenseUseCases.ReadPolicies readPolicies;
    private final SuspenseUseCases.Review review;

    public SuspenseController(UseCaseExecutor executor, Database database,
                              MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readPolicies = new SuspenseUseCases.ReadPolicies(database);
        this.review = new SuspenseUseCases.Review(database);
    }

    /** Une politique de suspens, a deux : nature, anciennete toleree en jours ouvres, responsable. */
    @PostMapping("/suspense-policies")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View setPolicy(Caller caller, @PathVariable UUID legalEntityId,
                                       @RequestBody Requests.SuspensePolicyRequest body) {
        // Nature, tolerance et responsable se valident a la soumission : un valideur ne doit pas
        // decouvrir une demande fausse.
        if (body.validFrom() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
        }
        if (body.kind() == null
            || java.util.Arrays.stream(Suspense.Kind.values())
                .noneMatch(k -> k.name().equalsIgnoreCase(body.kind().trim()))) {
            throw new IllegalArgumentException("Nature de suspens inconnue : " + body.kind()
                + " (" + java.util.Arrays.toString(Suspense.Kind.values()) + ")");
        }
        if (body.maxBusinessDays() == null || body.maxBusinessDays() < 0) {
            throw new IllegalArgumentException(
                "L'anciennete toleree est un nombre de jours ouvres positif ou nul");
        }
        if (body.owner() == null || body.owner().isBlank()) {
            throw new IllegalArgumentException("Un suspens a un responsable");
        }
        return makerChecker.submit(caller, legalEntityId, "SUSPENSE_POLICY_SET", Payloads.of(
            "kind", body.kind(), "maxBusinessDays", body.maxBusinessDays(), "owner", body.owner(),
            "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/suspense-policies")
    public List<Suspense.Policy> policies(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readPolicies, new SuspenseUseCases.EntityQuery(legalEntityId));
    }

    /** La revue des suspens a la date comptable, du plus ancien au plus recent. */
    @GetMapping("/suspense")
    public List<Suspense.Item> review(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, review, new SuspenseUseCases.EntityQuery(legalEntityId));
    }
}
