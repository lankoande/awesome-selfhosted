package io.corebanking.api.web;

import io.corebanking.api.usecase.RegulatoryUseCases;
import io.corebanking.regulatory.RegulatoryDeclarations;
import io.corebanking.regulatory.ReportFilings;
import io.corebanking.regulatory.ReportingService;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Declarations reglementaires : le catalogue, les etats, les echeances.
 *
 * <p>Deux actes se decident a deux, pour la meme raison qu'ils engagent la banque devant son
 * superviseur : <b>declarer</b> ce qu'elle doit, et <b>transmettre</b> ce qu'elle a produit.
 * Produire, entre les deux, est un travail comptable qu'un seul fait — et qui se refait, tant
 * que rien n'est parti.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/regulatory")
public class RegulatoryController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final RegulatoryUseCases.ReadDeclarations readDeclarations;
    private final RegulatoryUseCases.ProduceReport produceReport;
    private final RegulatoryUseCases.ReadFilings readFilings;
    private final RegulatoryUseCases.ReadFiling readFiling;
    private final RegulatoryUseCases.CancelFiling cancelFiling;
    private final RegulatoryUseCases.RecordConsent recordConsent;
    private final RegulatoryUseCases.ReadDeadlines readDeadlines;
    private final RegulatoryUseCases.ReadTaxRules readTaxRules;
    private final RegulatoryUseCases.ReadStatementPacks readPacks;
    private final RegulatoryUseCases.ReadConsolidationScopes readScopes;

    public RegulatoryController(UseCaseExecutor executor,
                                io.corebanking.ledger.store.Database database,
                                ReportingService reporting, MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.readDeclarations = new RegulatoryUseCases.ReadDeclarations(database);
        this.produceReport = new RegulatoryUseCases.ProduceReport(database, reporting);
        this.readFilings = new RegulatoryUseCases.ReadFilings(database);
        this.readFiling = new RegulatoryUseCases.ReadFiling(database, reporting);
        this.cancelFiling = new RegulatoryUseCases.CancelFiling(database);
        this.recordConsent = new RegulatoryUseCases.RecordConsent(database, reporting);
        this.readDeadlines = new RegulatoryUseCases.ReadDeadlines(database, reporting);
        this.readTaxRules = new RegulatoryUseCases.ReadTaxRules(database);
        this.readPacks = new RegulatoryUseCases.ReadStatementPacks(database);
        this.readScopes = new RegulatoryUseCases.ReadConsolidationScopes(database);
    }

    // ------------------------------------------------------------------ catalogue

    @PostMapping("/declarations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declare(Caller caller, @PathVariable UUID legalEntityId,
                                     @RequestBody Requests.DeclarationRequest body) {
        // Ce qu'un valideur ne doit pas decouvrir : une methode inconnue, un delai absent, un
        // seuil pose sur une methode qui n'en admet pas.
        RegulatoryDeclarations.Method method = method(body.method());
        RegulatoryDeclarations.Recipient recipient = recipient(body.recipient());
        RegulatoryDeclarations.Frequency frequency = frequency(body.frequency());
        if (body.code() == null || body.code().isBlank() || body.validFrom() == null
            || body.label() == null || body.label().isBlank()) {
            throw new IllegalArgumentException("Une declaration porte son code, son libelle et sa "
                + "date d'entree en vigueur");
        }
        RegulatoryDeclarations.requireDeadline(body.deadlineDays());
        RegulatoryDeclarations.requireThreshold(method, body.thresholdAmount());
        return makerChecker.submit(caller, legalEntityId, "REGULATORY_DECLARATION_DECLARE",
            Payloads.of("code", body.code(), "label", body.label(),
                "recipient", recipient.name(), "method", method.name(),
                "frequency", frequency.name(), "deadlineDays", body.deadlineDays(),
                "thresholdAmount", body.thresholdAmount() == null ? null
                                   : body.thresholdAmount().toPlainString(),
                "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/declarations")
    public List<RegulatoryDeclarations.Declaration> declarations(
            Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readDeclarations,
                            new RegulatoryUseCases.EntityQuery(legalEntityId));
    }

    // ------------------------------------------------------------------ etats

    /** L'etat se calcule sur la periode close : seule sa date de fin est demandee. */
    @PostMapping("/declarations/{declarationId}/filings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportFilings.Filing produce(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID declarationId,
                                        @RequestBody Requests.FilingRequest body) {
        if (body.periodEnd() == null) {
            throw new IllegalArgumentException("La periode couverte est celle qui se termine a la "
                + "date demandee : periodEnd est obligatoire");
        }
        return executor.run(caller, produceReport, new RegulatoryUseCases.Production(
            legalEntityId, declarationId, body.periodEnd(), Callers.actorId(caller)));
    }

    @GetMapping("/filings")
    public List<ReportFilings.Filing> filings(Caller caller, @PathVariable UUID legalEntityId,
                                              @RequestParam(required = false) String status) {
        return executor.run(caller, readFilings,
                            new RegulatoryUseCases.FilingQuery(legalEntityId, status(status)));
    }

    /** L'etat, ses lignes, et — s'il a ete transmis — ce que donne son recalcul. */
    @GetMapping("/filings/{filingId}")
    public RegulatoryUseCases.FilingView filing(Caller caller, @PathVariable UUID legalEntityId,
                                                @PathVariable UUID filingId) {
        return executor.run(caller, readFiling,
                            new RegulatoryUseCases.OneFiling(legalEntityId, filingId));
    }

    @PostMapping("/filings/{filingId}/transmission")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View transmit(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID filingId,
                                      @RequestBody Requests.TransmissionRequest body) {
        if (body.reference() == null || body.reference().isBlank()) {
            throw new IllegalArgumentException("La transmission porte la reference rendue par le "
                + "destinataire : c'est elle qui prouve le depot");
        }
        return makerChecker.submit(caller, legalEntityId, "REGULATORY_REPORT_TRANSMIT",
            Payloads.of("filingId", filingId, "reference", body.reference(),
                        "transmittedOn", body.transmittedOn()));
    }

    /** Annulation d'un etat produit et non transmis : ce qui est parti ne s'annule pas. */
    @PostMapping("/filings/{filingId}/cancellation")
    public ReportFilings.Filing cancel(Caller caller, @PathVariable UUID legalEntityId,
                                       @PathVariable UUID filingId,
                                       @RequestBody Requests.FilingCancellation body) {
        if (body.reason() == null || body.reason().isBlank()) {
            throw new IllegalArgumentException("L'annulation d'un etat porte son motif");
        }
        return executor.run(caller, cancelFiling, new RegulatoryUseCases.Cancellation(
            legalEntityId, filingId, body.reason()));
    }

    // ------------------------------------------------------------------ echeances, consentement

    @GetMapping("/deadlines")
    public List<ReportingService.Overdue> deadlines(Caller caller,
                                                    @PathVariable UUID legalEntityId) {
        return executor.run(caller, readDeadlines,
                            new RegulatoryUseCases.EntityQuery(legalEntityId));
    }

    /** Le consentement du client : donne ou revoque, il se recueille au guichet. */
    @PostMapping("/parties/{partyId}/credit-bureau-consent")
    public Boolean consent(Caller caller, @PathVariable UUID legalEntityId,
                           @PathVariable UUID partyId,
                           @RequestBody Requests.ConsentRequest body) {
        if (body.granted() == null) {
            throw new IllegalArgumentException("Le consentement se donne ou se revoque : granted "
                + "est obligatoire");
        }
        return executor.run(caller, recordConsent, new RegulatoryUseCases.Consent(
            legalEntityId, partyId, body.granted(), Callers.actorId(caller)));
    }

    // ------------------------------------------------------------------ fiscalite

    @PostMapping("/tax-rules")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareTax(Caller caller, @PathVariable UUID legalEntityId,
                                        @RequestBody Requests.TaxRuleRequest body) {
        io.corebanking.regulatory.TaxRules.Basis basis = basis(body.basis());
        if (body.code() == null || body.code().isBlank() || body.label() == null
            || body.label().isBlank() || body.collectionAccountId() == null
            || body.validFrom() == null) {
            throw new IllegalArgumentException("Une taxe porte son code, son libelle, son compte "
                + "de collecte et sa date d'entree en vigueur");
        }
        io.corebanking.regulatory.TaxRules.requireRate(body.ratePercent());
        return makerChecker.submit(caller, legalEntityId, "TAX_RULE_DECLARE", Payloads.of(
            "code", body.code(), "label", body.label(), "basis", basis.name(),
            "ratePercent", body.ratePercent().toPlainString(),
            "collectionAccountId", body.collectionAccountId(),
            "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/tax-rules")
    public List<io.corebanking.regulatory.TaxRules.Rule> taxRules(
            Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readTaxRules,
                            new RegulatoryUseCases.EntityQuery(legalEntityId));
    }

    // ------------------------------------------------------------------ liasse, groupe

    @PostMapping("/statement-packs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declarePack(Caller caller, @PathVariable UUID legalEntityId,
                                         @RequestBody Requests.StatementPackRequest body) {
        // La composition se valide a la soumission : un valideur ne doit pas decouvrir une
        // liasse qui ne rapproche rien.
        List<io.corebanking.ledger.store.StatementLayouts.Kind> items =
            io.corebanking.regulatory.StatementPacks.requireComposition(kinds(body.items()));
        if (body.code() == null || body.code().isBlank() || body.label() == null
            || body.label().isBlank() || body.validFrom() == null) {
            throw new IllegalArgumentException("Une liasse porte son code, son libelle et sa date "
                + "d'entree en vigueur");
        }
        return makerChecker.submit(caller, legalEntityId, "STATEMENT_PACK_DECLARE", Payloads.of(
            "code", body.code(), "label", body.label(),
            "items", items.stream().map(Enum::name).collect(Collectors.joining(",")),
            "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @GetMapping("/statement-packs")
    public List<io.corebanking.regulatory.StatementPacks.Pack> statementPacks(
            Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readPacks, new RegulatoryUseCases.EntityQuery(legalEntityId));
    }

    @PostMapping("/consolidation-scopes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareScope(Caller caller, @PathVariable UUID legalEntityId,
                                          @RequestBody Requests.ConsolidationScopeRequest body) {
        if (body.members() == null || body.members().isEmpty()) {
            throw new IllegalArgumentException("Un perimetre de consolidation a des membres");
        }
        StringBuilder members = new StringBuilder();
        for (Requests.ConsolidationMemberRequest member : body.members()) {
            if (member.entityId() == null || member.interestPercent() == null) {
                throw new IllegalArgumentException("Un membre porte son entite et sa quote-part");
            }
            if (!members.isEmpty()) {
                members.append(';');
            }
            members.append(member.entityId()).append(':')
                   .append(consolidationMethod(member.method()).name())
                   .append(':').append(member.interestPercent().toPlainString());
        }
        return makerChecker.submit(caller, legalEntityId, "CONSOLIDATION_SCOPE_DECLARE",
            Payloads.of("code", body.code(), "label", body.label(),
                "presentationCurrency", body.presentationCurrency(),
                "members", members.toString(),
                "validFrom", body.validFrom(), "validTo", body.validTo()));
    }

    @PostMapping("/consolidation-scopes/{scopeId}/eliminations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View declareElimination(Caller caller, @PathVariable UUID legalEntityId,
                                                @PathVariable UUID scopeId,
                                                @RequestBody Requests.EliminationRequest body) {
        if (body.label() == null || body.label().isBlank() || body.leftAccountId() == null
            || body.rightAccountId() == null || body.leftEntityId() == null
            || body.rightEntityId() == null) {
            throw new IllegalArgumentException("Une elimination porte son libelle et les deux "
                + "comptes qui se font face, avec leurs entites");
        }
        return makerChecker.submit(caller, legalEntityId, "CONSOLIDATION_ELIMINATION_DECLARE",
            Payloads.of("scopeId", scopeId, "label", body.label(),
                "leftEntityId", body.leftEntityId(), "leftAccountId", body.leftAccountId(),
                "rightEntityId", body.rightEntityId(), "rightAccountId", body.rightAccountId()));
    }

    @GetMapping("/consolidation-scopes")
    public List<io.corebanking.regulatory.ConsolidationScopes.Scope> consolidationScopes(
            Caller caller, @PathVariable UUID legalEntityId) {
        return executor.run(caller, readScopes, new RegulatoryUseCases.EntityQuery(legalEntityId));
    }

    // ------------------------------------------------------------------ outillage

    private static RegulatoryDeclarations.Method method(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : method");
        }
        try {
            return RegulatoryDeclarations.Method.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Methode de declaration inconnue : " + value
                + " (ACCOUNTING_SITUATION, CREDIT_REGISTRY, PAYMENT_INCIDENTS, CREDIT_BUREAU)");
        }
    }

    private static RegulatoryDeclarations.Recipient recipient(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : recipient");
        }
        try {
            return RegulatoryDeclarations.Recipient.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Destinataire inconnu : " + value
                + " (CENTRAL_BANK, BANKING_COMMISSION, CREDIT_BUREAU, TAX_AUTHORITY)");
        }
    }

    private static RegulatoryDeclarations.Frequency frequency(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : frequency");
        }
        try {
            return RegulatoryDeclarations.Frequency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Periodicite inconnue : " + value
                + " (MONTHLY, QUARTERLY, YEARLY)");
        }
    }

    private static List<io.corebanking.ledger.store.StatementLayouts.Kind> kinds(
            List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Une liasse cite les etats qui la composent");
        }
        List<io.corebanking.ledger.store.StatementLayouts.Kind> kinds = new java.util.ArrayList<>();
        for (String value : values) {
            try {
                kinds.add(io.corebanking.ledger.store.StatementLayouts.Kind.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Nature d'etat inconnue : " + value
                    + " (BALANCE_SHEET, INCOME_STATEMENT, OFF_BALANCE_SHEET)");
            }
        }
        return kinds;
    }

    private static io.corebanking.regulatory.ConsolidationScopes.Method consolidationMethod(
            String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : method");
        }
        try {
            return io.corebanking.regulatory.ConsolidationScopes.Method.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Methode de consolidation inconnue : " + value
                + " (FULL, PROPORTIONAL, EQUITY)");
        }
    }

    private static io.corebanking.regulatory.TaxRules.Basis basis(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : basis");
        }
        try {
            return io.corebanking.regulatory.TaxRules.Basis.valueOf(
                value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Assiette de taxe inconnue : " + value
                + " (INTEREST_PAID, FEES_CHARGED, TRANSACTION)");
        }
    }

    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PRODUCED", "TRANSMITTED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Statut d'etat inconnu : " + value
                + " (PRODUCED, TRANSMITTED, CANCELLED)");
        }
        return status;
    }
}
