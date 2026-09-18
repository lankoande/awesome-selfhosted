package io.corebanking.api.web;

import io.corebanking.api.usecase.ProductUseCases;
import io.corebanking.interest.rate.Tier;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Parametrage produit : une version se redige, puis s'active a deux. */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/products")
public class ProductController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final ProductUseCases.CreateDraft draft;
    private final ProductUseCases.ListOpenable openable;

    public ProductController(UseCaseExecutor executor, Database database,
                             MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draft = new ProductUseCases.CreateDraft(database);
        this.openable = new ProductUseCases.ListOpenable(database);
    }

    /**
     * Les produits ouvrables a une date : ce qu'un compte peut citer.
     *
     * <p>Sans cette lecture, un code produit se saisit de memoire au guichet, et l'ouverture se
     * refuse au bout de la chaine pour une faute de frappe — apres que le client a signe.
     */
    @GetMapping
    public java.util.List<io.corebanking.product.ProductCatalog.Openable> openable(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate on) {
        return executor.run(caller, openable, new ProductUseCases.Catalogue(legalEntityId, on));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draft(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.ProductDraft body) {
        List<Tier> tiers = body.tiers() == null ? List.of()
            : body.tiers().stream().map(t -> new Tier(
                new BigDecimal(t.from()), t.to() == null ? null : new BigDecimal(t.to()),
                new BigDecimal(t.annualRatePercent()))).toList();
        UUID id = executor.run(caller, draft, new ProductUseCases.Draft(
            legalEntityId, body.code(), body.productType(), body.label(), body.currency(),
            body.validFrom(), body.validTo(), body.parameters(), tiers, Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    /** L'activation est soumise, puis approuvee par un second — jamais le redacteur de la version. */
    @PostMapping("/{versionId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID versionId) {
        return makerChecker.submit(caller, legalEntityId, "PRODUCT_ACTIVATE",
                                   Payloads.of("versionId", versionId));
    }
}
