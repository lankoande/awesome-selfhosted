package io.corebanking.api.web;

import io.corebanking.api.usecase.ParameterUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;
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

    public AccountingSchemaController(UseCaseExecutor executor, Database database,
                                      MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draft = new ParameterUseCases.DraftAccountingSchema(database);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draft(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.AccountingSchemaDraft body) {
        UUID id = executor.run(caller, draft, new ParameterUseCases.AccountingSchemaDraft(
            legalEntityId, body.code(), body.label(), body.currency(), body.validFrom(),
            body.validTo(), schema(body), Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/{schemaId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID schemaId) {
        return makerChecker.submit(caller, legalEntityId, "ACCOUNTING_SCHEMA_ACTIVATE",
                                   Payloads.of("schemaId", schemaId));
    }

    /** Le schema tel que le moteur le construit : derivations dans l'ordre, lignes conditionnelles. */
    private static AccountingSchema schema(Requests.AccountingSchemaDraft body) {
        if (body.code() == null || body.events() == null || body.events().isEmpty()) {
            throw new IllegalArgumentException("Un schema porte un code et au moins un evenement");
        }
        AccountingSchema.Builder schema = AccountingSchema.of(
            body.code(), body.version() == null ? 1 : body.version());
        for (Requests.SchemaEvent event : body.events()) {
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
