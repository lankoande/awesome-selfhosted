package io.corebanking.api.web;

import io.corebanking.api.usecase.LayoutUseCases;
import io.corebanking.api.usecase.LedgerUseCases;
import io.corebanking.api.usecase.ParameterUseCases;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.StatementLayouts;
import io.corebanking.ledger.store.Statements;
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
 * Maquettes d'etats financiers : redigees par la comptabilite, verifiees avant d'entrer en base,
 * activees a deux. Une maquette se relit, rubriques et regles : ce qui a produit un etat.
 */
@RestController
@RequestMapping("/v1/entities/{legalEntityId}/statement-layouts")
public class StatementLayoutController {

    private final UseCaseExecutor executor;
    private final MakerChecker makerChecker;
    private final ParameterUseCases.DraftStatementLayout draft;
    private final LedgerUseCases.ReadStatementLayout read;
    private final LayoutUseCases.ListLayouts layouts;
    private final LayoutUseCases.PreviewStatement preview;
    private final LayoutUseCases.WithdrawDraft withdraw;

    public StatementLayoutController(UseCaseExecutor executor, Database database,
                                     MakerChecker makerChecker) {
        this.executor = executor;
        this.makerChecker = makerChecker;
        this.draft = new ParameterUseCases.DraftStatementLayout(database);
        this.read = new LedgerUseCases.ReadStatementLayout(database);
        this.layouts = new LayoutUseCases.ListLayouts(database);
        this.preview = new LayoutUseCases.PreviewStatement(database);
        this.withdraw = new LayoutUseCases.WithdrawDraft(database);
    }

    /**
     * Les maquettes de l'entite, brouillons compris.
     *
     * <p>Sans cette lecture, l'identifiant d'un brouillon n'existait que dans la reponse du POST
     * qui l'avait cree : perdu au rechargement, le brouillon devenait inactivable, et la maquette
     * qui a produit le dernier bilan transmis ne se retrouvait nulle part.
     */
    @GetMapping
    public java.util.List<StatementLayouts.Summary> layouts(
            Caller caller, @PathVariable UUID legalEntityId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status) {
        return executor.run(caller, layouts, new LayoutUseCases.LayoutQuery(
            legalEntityId,
            kind == null || kind.isBlank() ? null
                : enumOf(StatementLayouts.Kind.class, kind, "kind"),
            status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requests.Created draft(Caller caller, @PathVariable UUID legalEntityId,
                                  @RequestBody Requests.StatementLayoutDraft body) {
        UUID id = executor.run(caller, draft, layout(legalEntityId, body, Callers.actorId(caller)));
        return new Requests.Created(id);
    }

    @PostMapping("/{layoutId}/activation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View activate(Caller caller, @PathVariable UUID legalEntityId,
                                      @PathVariable UUID layoutId) {
        return makerChecker.submit(caller, legalEntityId, "STATEMENT_LAYOUT_ACTIVATE",
                                   Payloads.of("layoutId", layoutId));
    }

    @GetMapping("/{layoutId}")
    public StatementLayouts.Layout read(Caller caller, @PathVariable UUID legalEntityId,
                                        @PathVariable UUID layoutId) {
        return executor.run(caller, read, new LedgerUseCases.LayoutLookup(legalEntityId, layoutId));
    }

    /**
     * Essaie une maquette sur le journal, sans rien produire d'officiel.
     *
     * <p>C'est la seule facon d'eprouver un brouillon. Sans elle, on activait a deux une maquette
     * qui laisse des comptes sans rubrique, et on l'apprenait en lisant un bilan faux — apres
     * l'avoir transmis. Les controles sont ceux de la production : un essai indulgent ne vaudrait
     * rien.
     */
    @GetMapping("/{layoutId}/preview")
    public Statements.Statement preview(
            Caller caller, @PathVariable UUID legalEntityId, @PathVariable UUID layoutId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return executor.run(caller, preview,
                            new LayoutUseCases.Trial(legalEntityId, layoutId, from, to));
    }

    /**
     * Ferme la validite d'une maquette active, a deux.
     *
     * <p>Une seule maquette active par nature d'etat et par date : c'est la fermeture qui libere
     * la place pour la suivante.
     */
    @PostMapping("/{layoutId}/closure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MakerChecker.View close(Caller caller, @PathVariable UUID legalEntityId,
                                   @PathVariable UUID layoutId,
                                   @RequestBody Requests.StatementLayoutClosure body) {
        if (body.validTo() == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : validTo");
        }
        return makerChecker.submit(caller, legalEntityId, "STATEMENT_LAYOUT_CLOSE", Payloads.of(
            "layoutId", layoutId, "validTo", body.validTo()));
    }

    /** Retire un brouillon abandonne. Seul acte du parametrage des etats qui ne soit pas a deux. */
    @PostMapping("/{layoutId}/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(Caller caller, @PathVariable UUID legalEntityId,
                         @PathVariable UUID layoutId) {
        executor.run(caller, withdraw, new LayoutUseCases.Withdrawal(
            legalEntityId, layoutId, Callers.actorId(caller)));
    }

    /** La maquette telle que le socle la verifie ; une valeur inconnue est une requete fausse. */
    private static StatementLayouts.Draft layout(UUID legalEntityId,
                                                 Requests.StatementLayoutDraft body, UUID author) {
        List<StatementLayouts.Line> lines = new ArrayList<>();
        for (Requests.StatementLineRequest line : body.lines() == null
                ? List.<Requests.StatementLineRequest>of() : body.lines()) {
            lines.add(new StatementLayouts.Line(
                integer(line.ordinal(), "ordinal"), line.code(), line.label(),
                line.level() == null ? 0 : line.level(),
                enumOf(StatementLayouts.LineKind.class, line.kind(), "kind"),
                enumOf(Direction.class, line.side(), "side"), line.plus(), line.minus()));
        }
        List<StatementLayouts.Rule> rules = new ArrayList<>();
        for (Requests.StatementRuleRequest rule : body.rules() == null
                ? List.<Requests.StatementRuleRequest>of() : body.rules()) {
            rules.add(new StatementLayouts.Rule(
                integer(rule.ordinal(), "ordinal"), rule.lineCode(),
                rule.accountKind() == null ? null
                    : enumOf(AccountKind.class, rule.accountKind(), "accountKind"),
                rule.codePrefix(),
                rule.balanceSide() == null ? null
                    : enumOf(Direction.class, rule.balanceSide(), "balanceSide")));
        }
        return new StatementLayouts.Draft(
            legalEntityId, enumOf(StatementLayouts.Kind.class, body.kind(), "kind"), body.code(),
            body.label(), body.validFrom(), body.validTo(), lines, rules, author);
    }

    private static int integer(Integer value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + what);
        }
        return value;
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
