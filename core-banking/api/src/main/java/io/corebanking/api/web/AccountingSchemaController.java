package io.corebanking.api.web;

import io.corebanking.api.usecase.ParameterUseCases;
import io.corebanking.api.usecase.SchemaUseCases;
import io.corebanking.api.usecase.StandardSchemas;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaSimulator;
import io.corebanking.schema.TemplateLine;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Schemas comptables : la traduction de chaque evenement en lignes d'ecriture. Redige par la
 * comptabilite, valide avant d'entrer en base — un schema desequilibre n'y entre jamais —, active
 * a deux.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/accounting-schemas")
public class AccountingSchemaController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final ParameterUseCases.DraftAccountingSchema draft;
    private final SchemaUseCases.ReadStandard standard;
    private final SchemaUseCases.ListSchemas schemas;
    private final SchemaUseCases.ReadSchema schema;
    private final SchemaUseCases.Simulate simulate;
    private final SchemaUseCases.WithdrawDraft withdraw;

    public AccountingSchemaController(UseCaseExecutor executor, Database database,
                                      MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draft = new ParameterUseCases.DraftAccountingSchema(database);
        this.standard = new SchemaUseCases.ReadStandard(database);
        this.schemas = new SchemaUseCases.ListSchemas(database);
        this.schema = new SchemaUseCases.ReadSchema(database);
        this.simulate = new SchemaUseCases.Simulate(database);
        this.withdraw = new SchemaUseCases.WithdrawDraft(database);
    }

    // ------------------------------------------------------------------ ce que le socle impute

    /**
     * Le catalogue des evenements comptabilises par le socle.
     *
     * <p>Il repond a deux questions qu'on ne pouvait poser qu'au code : <i>qu'impute la banque
     * quand un client retire de l'argent ?</i> et <i>que puis-je reellement parametrer ?</i> La
     * seconde est la plus importante : un schema redige pour un evenement que personne ne resout
     * s'activerait a deux et ne serait lu par personne.
     */
    @GetMapping("/standard")
    public List<StandardSchemas.Event> standard(Caller caller, @PathVariable UUID legalEntityId,
                                                @RequestParam(required = false) String currency) {
        return executor.run(caller, standard,
                            new SchemaUseCases.CatalogueQuery(legalEntityId, currency));
    }

    // ------------------------------------------------------------------ les schemas parametres

    /**
     * Les schemas de l'entite, brouillons compris.
     *
     * <p>Sans cette lecture, l'identifiant d'un brouillon n'existait que dans la reponse du POST
     * qui l'avait cree : perdu au rechargement, le brouillon devenait inactivable.
     */
    @GetMapping
    public List<SchemaCatalog.Summary> schemas(Caller caller, @PathVariable UUID legalEntityId,
                                               @RequestParam(required = false) String code,
                                               @RequestParam(required = false) String status) {
        return executor.run(caller, schemas,
                            new SchemaUseCases.SchemaQuery(legalEntityId, code, status));
    }

    /** Un schema en entier : en-tete, derivations dans l'ordre, lignes, variables attendues. */
    @GetMapping("/{schemaId}")
    public SchemaCatalog.Detail schema(Caller caller, @PathVariable UUID legalEntityId,
                                       @PathVariable UUID schemaId) {
        return executor.run(caller, schema,
                            new SchemaUseCases.SchemaLookup(legalEntityId, schemaId));
    }

    // ------------------------------------------------------------------ l'essai

    /**
     * Essaie un schema sur un cas, sans rien imputer.
     *
     * <p>Personne ne lit {@code round(net, 0) + round(tax, 0)} et n'en deduit l'ecriture : on la
     * lit en la posant sur un cas. L'essai rend les variables derivees, les lignes retenues et
     * celles qui ne le sont pas avec leur raison, puis les totaux — y compris quand le moteur
     * refuserait, car c'est justement ce refus qu'on cherche a comprendre.
     */
    @PostMapping("/trials")
    public SchemaSimulator.Outcome trial(Caller caller, @PathVariable UUID legalEntityId,
                                         @RequestBody Requests.SchemaTrial body) {
        AccountingSchema written = body.events() == null || body.events().isEmpty()
            ? null
            : schema(body.code() == null ? "ESSAI" : body.code(), 1, body.events());
        return executor.run(caller, simulate, new SchemaUseCases.Trial(
            legalEntityId, written, body.schemaId(), body.eventType(), body.currency(),
            body.values() == null ? Map.<String, BigDecimal>of() : body.values()));
    }

    // ------------------------------------------------------------------ redaction et fin de vie

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draft(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.AccountingSchemaDraft body) {
        AccountingSchema written = schema(body.code(), body.version() == null ? 1 : body.version(),
                                          body.events());
        // Refus avant toute ecriture : un schema que personne ne resoudrait n'entre pas en base.
        StandardSchemas.requireOverridable(written.templates().keySet());
        UUID id = executor.run(caller, draft, new ParameterUseCases.AccountingSchemaDraft(
            legalEntityId, body.code(), body.label(), body.currency(), body.validFrom(),
            body.validTo(), written, Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    /** L'activation est soumise, puis approuvee par un second — jamais le redacteur du schema. */
    @PostMapping("/{schemaId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID schemaId) {
        return makerChecker.submit(caller, legalEntityId, "ACCOUNTING_SCHEMA_ACTIVATE",
                                   Payloads.of("schemaId", schemaId));
    }

    /**
     * Ferme la validite d'un schema actif, a deux.
     *
     * <p>C'est ainsi qu'un schema cesse de s'appliquer, et c'est aussi ce qui permet d'en activer
     * un suivant sous le meme code : la contrainte d'exclusion interdit deux validites actives qui
     * se croisent.
     */
    @PostMapping("/{schemaId}/closure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View close(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID schemaId,
                                   @RequestBody Requests.AccountingSchemaClosure body) {
        if (body.validTo() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validTo");
        }
        return makerChecker.submit(caller, legalEntityId, "ACCOUNTING_SCHEMA_CLOSE", Payloads.of(
            "schemaId", schemaId, "validTo", body.validTo()));
    }

    /** Retire un brouillon abandonne. Seul acte du parametrage comptable qui ne soit pas a deux. */
    @PostMapping("/{schemaId}/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(Caller caller, @PathVariable UUID legalEntityId,
                         @PathVariable UUID schemaId) {
        executor.run(caller, withdraw, new SchemaUseCases.Withdrawal(
            legalEntityId, schemaId, Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ interne

    /** Le schema tel que le moteur le construit : derivations dans l'ordre, lignes conditionnelles. */
    private static AccountingSchema schema(String code, int version,
                                           List<Requests.SchemaEvent> events) {
        if (code == null || events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Un schema porte un code et au moins un evenement");
        }
        AccountingSchema.Builder schema = AccountingSchema.of(code, version);
        Set<String> seen = new LinkedHashSet<>();
        for (Requests.SchemaEvent event : events) {
            if (event.eventType() == null || event.eventType().isBlank()) {
                throw new IllegalArgumentException("Evenement sans type");
            }
            if (!seen.add(event.eventType())) {
                throw new IllegalArgumentException(
                    "Evenement declare deux fois : " + event.eventType());
            }
            EventTemplate.Builder template = EventTemplate.of(event.eventType());
            if (event.derivations() != null) {
                event.derivations().forEach(template::derive);
            }
            if (event.lines() == null || event.lines().isEmpty()) {
                throw new IllegalArgumentException(
                    "L'evenement " + event.eventType() + " n'a aucune ligne");
            }
            for (Requests.SchemaLine line : event.lines()) {
                TemplateLine built = "CREDIT".equalsIgnoreCase(line.direction())
                    ? TemplateLine.credit(line.account(), line.amount(), line.label())
                    : TemplateLine.debit(line.account(), line.amount(), line.label());
                if (line.condition() != null && !line.condition().isBlank()) {
                    built = built.onlyIf(line.condition());
                }
                template.line(built);
            }
            schema.on(template.build());
        }
        return schema.build();
    }
}
