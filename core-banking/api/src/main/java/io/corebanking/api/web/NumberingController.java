package io.corebanking.api.web;

import io.corebanking.api.usecase.EstablishmentUseCases;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Numbering;
import io.corebanking.security.Caller;
import io.corebanking.security.UseCaseExecutor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Le plan de numerotation : comment la banque compose ses numeros de clients, de comptes, de
 * dossiers.
 *
 * <p>Une regle se redige, se relit avec le numero qu'elle produirait, puis s'active a deux. Rien
 * n'est seme a la creation d'un etablissement : le socle <b>propose</b> un gabarit par domaine,
 * la banque choisit. C'est elle qui vivra vingt ans avec, et elle que le superviseur interrogera.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/numbering-rules")
public class NumberingController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final EstablishmentUseCases.ReadNumberingRules read;
    private final EstablishmentUseCases.PreviewNumber preview;
    private final EstablishmentUseCases.DraftNumberingRule draft;

    public NumberingController(UseCaseExecutor executor, Database database,
                               MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.read = new EstablishmentUseCases.ReadNumberingRules(database);
        this.preview = new EstablishmentUseCases.PreviewNumber(database);
        this.draft = new EstablishmentUseCases.DraftNumberingRule(database);
    }

    /** Les regles de l'entite : brouillons, active, retirees. */
    @GetMapping
    public List<Numbering.Rule> list(Caller caller, @PathVariable UUID legalEntityId,
                                     @RequestParam(required = false) String domain) {
        return executor.run(caller, read, new EstablishmentUseCases.RuleQuery(
            legalEntityId, domain == null || domain.isBlank() ? null
                : enumOf(Numbering.Domain.class, domain, "domain")));
    }

    /**
     * Le gabarit que le socle propose pour ce domaine.
     *
     * <p>Une proposition, pas un defaut impose : elle revient en brouillon non enregistre, a
     * relire et a adapter avant d'etre redigee.
     */
    @GetMapping("/proposals/{domain}")
    public Numbering.Draft proposal(Caller caller, @PathVariable UUID legalEntityId,
                                    @PathVariable String domain) {
        executor.run(caller, read, new EstablishmentUseCases.RuleQuery(legalEntityId, null));
        return Numbering.proposal(legalEntityId, enumOf(Numbering.Domain.class, domain, "domain"),
                                  Callers.actorId(caller));
    }

    /** Ce que la regle composerait maintenant, sans consommer le compteur. */
    @GetMapping("/{ruleId}/preview")
    public Preview preview(Caller caller, @PathVariable UUID legalEntityId,
                           @PathVariable UUID ruleId,
                           @RequestParam(required = false) UUID branchId,
                           @RequestParam(required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        UUID branch = branchId == null ? Callers.branchId(caller) : branchId;
        return new Preview(executor.run(caller, preview,
            new EstablishmentUseCases.PreviewQuery(legalEntityId, ruleId, branch, on)));
    }

    /** Le numero qu'une regle produirait. */
    public record Preview(String value) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draft(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.NumberingRuleDraft body) {
        UUID id = executor.run(caller, draft, rule(legalEntityId, body, Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/{ruleId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID ruleId) {
        return makerChecker.submit(caller, legalEntityId, "NUMBERING_ACTIVATE",
                                   Payloads.of("ruleId", ruleId));
    }

    /** La regle telle que le socle la verifie ; un gabarit mal forme est refuse ici. */
    private static Numbering.Draft rule(UUID legalEntityId, Requests.NumberingRuleDraft body,
                                        UUID author) {
        List<Numbering.Segment> segments = new ArrayList<>();
        for (Requests.NumberingSegmentRequest segment : body.segments() == null
                ? List.<Requests.NumberingSegmentRequest>of() : body.segments()) {
            segments.add(new Numbering.Segment(
                enumOf(Numbering.SegmentKind.class, segment.kind(), "kind"),
                segment.literalValue(), segment.length(),
                segment.padChar() == null || segment.padChar().isEmpty() ? null
                    : segment.padChar().charAt(0),
                segment.datePattern(),
                segment.checkAlgorithm() == null || segment.checkAlgorithm().isBlank() ? null
                    : enumOf(Numbering.CheckAlgorithm.class, segment.checkAlgorithm(),
                             "checkAlgorithm")));
        }
        return new Numbering.Draft(
            legalEntityId, enumOf(Numbering.Domain.class, body.domain(), "domain"), body.label(),
            segments,
            body.sequenceScope() == null || body.sequenceScope().isBlank() ? null
                : enumOf(Numbering.Scope.class, body.sequenceScope(), "sequenceScope"),
            body.sequenceReset() == null || body.sequenceReset().isBlank() ? null
                : enumOf(Numbering.Reset.class, body.sequenceReset(), "sequenceReset"),
            body.sequenceStart() == null ? 1 : body.sequenceStart(), author);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + what);
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " inconnu : " + value);
        }
    }
}
