package io.corebanking.api.web;

import io.corebanking.ledger.store.Database;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.AuthorizationService;
import io.corebanking.security.Caller;
import io.corebanking.security.Operation;
import io.corebanking.security.SecurityConfig;
import io.corebanking.security.store.PendingOperations;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Double validation.
 *
 * <p>Une operation que la politique soumet a un second regard ({@code AccessRule.dualControl})
 * n'est jamais executee par celui qui la saisit. Le maker est habilite a la <b>soumettre</b> ;
 * elle attend ; un checker habilite pour la meme operation, sur la meme cible, et qui n'est pas
 * le maker, l'<b>approuve</b> — et c'est alors, et seulement alors, qu'elle s'execute, avec le
 * maker pour auteur et le checker pour approbateur. Ni l'un ni l'autre ne sont des parametres
 * de la requete : ce sont les sujets des deux jetons.
 *
 * <p>Ce qui est garde en attente est la requete telle que recue, pas une commande deja
 * construite : a l'approbation, la requete est relue et rejouee par le meme cas d'usage, contre
 * l'etat du moment. Un compte ferme entre-temps refuse alors l'operation comme il l'aurait
 * refusee au guichet.
 */
public final class MakerChecker {

    private static final Logger LOG = LoggerFactory.getLogger(MakerChecker.class);

    /** Ce qu'un cas d'usage a double validation sait faire d'une requete en attente. */
    public interface Handler {
        /** Nom du cas d'usage, cle du registre. */
        String name();

        Operation operation();

        /** Cible de l'habilitation, lue dans la requete ; c'est ce que le maker et le checker doivent avoir le droit de viser. */
        AccessTarget targetOf(Caller maker, Map<String, Object> payload);

        /** Identifiant de l'objet vise, pour la lecture ; peut etre nul. */
        default String resourceOf(Map<String, Object> payload) {
            Object resource = payload.get("accountId");
            return resource == null ? null : resource.toString();
        }

        /** Execute la requete : le maker est l'auteur, le checker l'approbateur. */
        Object execute(Caller maker, Caller checker, Map<String, Object> payload);
    }

    /** Ce que l'API rend d'une operation en attente. */
    public record View(UUID id, String operation, String handler, String status, String makerId,
                       String makerUsername, Instant madeAt, Instant expiresAt, String decidedBy,
                       Instant decidedAt, String decisionReason, Map<String, Object> payload,
                       Object result, String error) {}

    private final Database database;
    private final AuthorizationService authorization;
    private final ObjectMapper json;
    private final Duration validity;
    private final Map<String, Handler> handlers = new LinkedHashMap<>();

    public MakerChecker(Database database, AuthorizationService authorization, ObjectMapper json,
                        Duration validity, List<Handler> handlers) {
        this.database = Objects.requireNonNull(database);
        this.authorization = Objects.requireNonNull(authorization);
        this.json = Objects.requireNonNull(json);
        this.validity = Objects.requireNonNull(validity);
        for (Handler handler : handlers) {
            if (!SecurityConfig.ruleFor(handler.operation()).dualControl()) {
                throw new IllegalStateException(
                    "Le cas d'usage " + handler.name() + " passe par la double validation alors "
                    + "que la politique ne l'exige pas pour " + handler.operation());
            }
            this.handlers.put(handler.name(), handler);
        }
    }

    /** Soumet une requete : le maker doit etre habilite a l'operation ; elle attend un checker. */
    public View submit(Caller maker, UUID legalEntityId, String handlerName,
                       Map<String, Object> payload) {
        Handler handler = require(handlerName);
        Map<String, Object> request = new LinkedHashMap<>(payload);
        request.put("legalEntityId", legalEntityId.toString());
        AccessTarget target = handler.targetOf(maker, request);
        authorization.require(maker, handler.operation(), target);

        UUID id = database.inTransaction(c -> PendingOperations.submit(c,
            new PendingOperations.Draft(legalEntityId, handler.operation().name(), handler.name(),
                                        handler.resourceOf(request), write(request),
                                        target.amount() == null ? null : target.amount().amount(),
                                        target.amount() == null ? null
                                                                : target.amount().currency().code(),
                                        maker, validity)));
        LOG.info("double validation : {} soumise par {} ({}), en attente {}", handler.name(),
                 maker.username(), maker.subjectId(), id);
        return read(maker, id);
    }

    /**
     * Approuve et execute. Le checker est habilite pour la meme operation et la meme cible, en
     * tant que valideur d'une operation faite par le maker — ce que la politique refuse si les
     * deux sont la meme personne.
     */
    public View approve(Caller checker, UUID id) {
        return decide(checker, id, true, null);
    }

    public View reject(Caller checker, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Un rejet se motive");
        }
        return decide(checker, id, false, reason);
    }

    private View decide(Caller checker, UUID id, boolean approved, String reason) {
        PendingOperations.Pending pending = database.inTransaction(
            c -> PendingOperations.require(c, id));
        if (!pending.legalEntityId().equals(checker.legalEntityId())) {
            throw new PendingOperations.UnknownPendingOperationException(id);
        }
        if (pending.expired(Instant.now())) {
            database.inTransaction(c -> { PendingOperations.expire(c, id); return null; });
            throw new NotDecidableException(id, "expiree le " + pending.expiresAt());
        }
        if (pending.status() != PendingOperations.Status.PENDING) {
            throw new NotDecidableException(id, "deja " + pending.status());
        }
        Handler handler = require(pending.handler());
        Map<String, Object> payload = parse(pending.payload());
        Caller maker = makerOf(pending, checker.legalEntityId());
        AccessTarget target = handler.targetOf(maker, payload).madeBy(pending.makerId());
        authorization.require(checker, handler.operation(), target);

        database.inTransaction(c -> {
            PendingOperations.decide(c, id, checker, approved, reason);
            return null;
        });
        if (!approved) {
            LOG.info("double validation : {} rejetee par {} — {}", id, checker.username(), reason);
            return read(checker, id);
        }
        try {
            Object result = database.inTransaction(c -> {
                Object executed = handler.execute(maker, checker, payload);
                PendingOperations.executed(c, id, write(executed));
                return executed;
            });
            LOG.info("double validation : {} approuvee par {} et executee", id, checker.username());
        } catch (RuntimeException e) {
            // L'execution a echoue apres une approbation valide : la decision reste, l'echec
            // aussi, et le maker recommence avec une requete qui tient compte de l'etat du jour.
            database.inTransaction(c -> { PendingOperations.failed(c, id, e.getMessage()); return null; });
            LOG.warn("double validation : {} approuvee mais non executee — {}", id, e.getMessage());
            throw e;
        }
        return read(checker, id);
    }

    /** Lecture : le maker, ou tout porteur habilite a l'operation sur l'entite. */
    public View read(Caller caller, UUID id) {
        PendingOperations.Pending pending = database.inTransaction(
            c -> PendingOperations.require(c, id));
        if (!pending.legalEntityId().equals(caller.legalEntityId())) {
            throw new PendingOperations.UnknownPendingOperationException(id);
        }
        if (!pending.makerId().equals(caller.subjectId())) {
            authorization.require(caller, Operation.valueOf(pending.operation()),
                                  AccessTarget.inEntity(pending.legalEntityId()));
        }
        return view(pending);
    }

    /** Ce qui attend dans l'entite de l'appelant, pour un porteur habilite a l'audit ou a l'operation. */
    /**
     * Les operations en attente que l'appelant peut voir — les siennes, et celles qu'il est
     * habilite a decider — par pages. Le filtre d'habilitation s'applique en memoire, puis la
     * page se decoupe : la liste est courte par construction, une operation en attente expire.
     */
    public io.corebanking.api.usecase.Paging.Paged<View> pending(
            Caller caller, io.corebanking.api.usecase.Paging.PageRequest page) {
        List<View> visible = database.inTransaction(
                c -> PendingOperations.pending(c, caller.legalEntityId()))
            .stream()
            .filter(p -> p.makerId().equals(caller.subjectId())
                         || authorization.decide(caller, Operation.valueOf(p.operation()),
                                                 AccessTarget.inEntity(p.legalEntityId()))
                                         .allowed())
            .map(this::view)
            .toList();
        return io.corebanking.api.usecase.Paging.Paged.slice(visible, page);
    }

    private View view(PendingOperations.Pending p) {
        return new View(p.id(), p.operation(), p.handler(), p.status().name(), p.makerId(),
                        p.makerUsername(), p.madeAt(), p.expiresAt(), p.decidedBy(), p.decidedAt(),
                        p.decisionReason(), parse(p.payload()),
                        p.result() == null ? null : parseAny(p.result()), p.error());
    }

    /** Le maker tel qu'il etait : c'est lui l'auteur de l'operation executee. */
    private static Caller makerOf(PendingOperations.Pending pending, UUID legalEntityId) {
        return new Caller(pending.makerId(), pending.makerUsername(), java.util.Set.of(),
                          legalEntityId, pending.makerBranchId());
    }

    private Handler require(String name) {
        Handler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalStateException("Cas d'usage a double validation inconnu : " + name);
        }
        return handler;
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String payload) {
        return json.readValue(payload, Map.class);
    }

    private Object parseAny(String payload) {
        return json.readValue(payload, Object.class);
    }

    /** L'operation en attente ne peut plus etre decidee. */
    public static class NotDecidableException extends RuntimeException {
        public NotDecidableException(UUID id, String detail) {
            super("Operation en attente " + id + " : " + detail);
        }
    }
}
