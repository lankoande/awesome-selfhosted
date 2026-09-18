package io.corebanking.api.web;

import io.corebanking.api.usecase.ProductUseCases;
import io.corebanking.interest.rate.Tier;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductFamily;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.math.BigDecimal;
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
    private final ProductUseCases.ReadFamilies families;
    private final ProductUseCases.ListVersions versions;
    private final ProductUseCases.ReadVersion version;
    private final ProductUseCases.WithdrawDraft withdraw;

    public ProductController(UseCaseExecutor executor, Database database,
                             MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draft = new ProductUseCases.CreateDraft(database);
        this.openable = new ProductUseCases.ListOpenable(database);
        this.families = new ProductUseCases.ReadFamilies();
        this.versions = new ProductUseCases.ListVersions(database);
        this.version = new ProductUseCases.ReadVersion(database);
        this.withdraw = new ProductUseCases.WithdrawDraft(database);
    }

    // ------------------------------------------------------------------ le contrat de parametrage

    /**
     * Les familles de produit et ce que chacune exige.
     *
     * <p>Un poste de parametrage construit sa saisie a partir d'ici. Sans cette lecture, il
     * porterait une copie des regles de {@code families.json} : deux copies divergent, et l'ecran
     * finirait par proposer un parametre que l'activation refuse.
     */
    @GetMapping("/families")
    public List<ProductFamily> families(Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, families, new ProductUseCases.Catalogues(legalEntityId));
    }

    // ------------------------------------------------------------------ les versions

    /**
     * Toutes les versions, brouillons compris.
     *
     * <p>A distinguer de {@code GET /products}, qui rend ce qui est <b>ouvrable</b> : l'un sert le
     * guichet, l'autre celui qui parametre. Sans celle-ci, l'identifiant d'un brouillon n'existait
     * que dans la reponse du POST qui l'avait cree — perdu au rechargement, le brouillon devenait
     * inactivable.
     */
    @GetMapping("/versions")
    public List<ProductCatalog.VersionSummary> versions(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String status) {
        return executor.run(caller, versions,
                            new ProductUseCases.VersionQuery(legalEntityId, code, status));
    }

    /** Une version en entier : en-tete, parametres, baremes. */
    @GetMapping("/versions/{versionId}")
    public ProductCatalog.Version version(Caller caller, @PathVariable UUID legalEntityId,
                                          @PathVariable UUID versionId) {
        return executor.run(caller, version,
                            new ProductUseCases.VersionLookup(legalEntityId, versionId));
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
        Map<String, List<Tier>> feeTiers = new LinkedHashMap<>();
        if (body.feeTiers() != null) {
            body.feeTiers().forEach((code, tiers) -> feeTiers.put(code, schedule(tiers)));
        }
        UUID id = executor.run(caller, draft, new ProductUseCases.Draft(
            legalEntityId, body.code(), body.productType(), body.label(), body.currency(),
            body.validFrom(), body.validTo(), body.parameters(), schedule(body.tiers()), feeTiers,
            Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    private static List<Tier> schedule(List<Requests.RateTier> tiers) {
        return tiers == null ? List.of()
            : tiers.stream().map(t -> new Tier(
                new BigDecimal(t.from()), t.to() == null ? null : new BigDecimal(t.to()),
                new BigDecimal(t.annualRatePercent()))).toList();
    }

    /** L'activation est soumise, puis approuvee par un second — jamais le redacteur de la version. */
    @PostMapping("/{versionId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID versionId) {
        return makerChecker.submit(caller, legalEntityId, "PRODUCT_ACTIVATE",
                                   Payloads.of("versionId", versionId));
    }

    /**
     * Ferme la validite d'une version active, a deux.
     *
     * <p>C'est ainsi qu'un produit cesse d'etre commercialise, et non par un changement d'etat :
     * les comptes rattaches resolvent leur parametrage a chaque date de valeur traitee, y compris
     * passee, et seule une version active se resout.
     */
    @PostMapping("/versions/{versionId}/closure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View close(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID versionId,
                                   @RequestBody Requests.ProductClosure body) {
        if (body.validTo() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validTo");
        }
        return makerChecker.submit(caller, legalEntityId, "PRODUCT_CLOSE", Payloads.of(
            "versionId", versionId, "validTo", body.validTo()));
    }

    /** Retire un brouillon abandonne. Seul acte du parametrage produit qui ne soit pas a deux. */
    @PostMapping("/versions/{versionId}/withdrawal")
    public ProductUseCases.Activation withdraw(Caller caller, @PathVariable UUID legalEntityId,
                                               @PathVariable UUID versionId) {
        return executor.run(caller, withdraw, new ProductUseCases.Withdrawal(
            legalEntityId, versionId, Callers.actorId(caller)));
    }
}
