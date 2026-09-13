package io.corebanking.security;

import io.corebanking.kernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * Point de controle unique des habilitations.
 *
 * <p>Toute operation protegee passe par {@link #require}. Il n'existe pas de second chemin : ni
 * annotation, ni verification locale dans un service, ni condition dans un controleur. Le controle
 * disperse est la facon dont une application finit avec deux regles differentes pour la meme
 * operation, sans que personne ne sache laquelle s'applique.
 */
public final class AuthorizationService {

    private final AuthorizationAudit audit;

    public AuthorizationService(AuthorizationAudit audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Autorise ou leve {@link AccessDeniedException}. Toute decision est tracee. */
    public void require(Caller caller, Operation operation, AccessTarget target) {
        AccessDecision decision = decide(caller, operation, target);
        if (!decision.allowed() || SecurityConfig.ruleFor(operation).auditEvenOnSuccess()) {
            audit.record(caller, operation, target, decision);
        }
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision);
        }
    }

    /**
     * Evalue sans lever d'exception ni tracer. Reserve aux usages d'affichage — masquer une action
     * qu'un utilisateur ne peut pas declencher. Ne remplace jamais {@link #require} au moment
     * d'agir : un controle d'affichage n'est pas un controle d'acces.
     */
    public AccessDecision decide(Caller caller, Operation operation, AccessTarget target) {
        AccessRule rule = SecurityConfig.ruleFor(operation);

        // 1. Cloisonnement des entites, evalue en premier. Une tentative transverse est un signal
        //    plus grave qu'un simple defaut de role, et doit etre qualifiee comme telle.
        if (rule.scope() != Scope.ANY_ENTITY
            && !caller.legalEntityId().equals(target.legalEntityId())) {
            return AccessDecision.deny(operation,
                "cloisonnement des entites : l'appelant releve de " + caller.legalEntityId()
                + ", l'objet de " + target.legalEntityId());
        }

        // 2. Role.
        if (!caller.hasAnyRole(rule.roles())) {
            return AccessDecision.deny(operation,
                "aucun des roles " + caller.roles() + " ne figure parmi " + rule.roles());
        }

        // 3. Perimetre d'agence.
        if (rule.scope() == Scope.OWN_BRANCH) {
            if (caller.branchId() == null) {
                return AccessDecision.deny(operation,
                    "operation limitee au perimetre d'agence, appelant sans agence de rattachement");
            }
            if (target.branchId() != null && !caller.branchId().equals(target.branchId())) {
                return AccessDecision.deny(operation,
                    "objet rattache a l'agence " + target.branchId()
                    + ", appelant rattache a " + caller.branchId());
            }
        }

        // 4. Plafond de montant.
        if (target.amount() != null && !rule.ceilings().isEmpty()) {
            Optional<Money> ceiling = highestCeiling(caller, rule, target.amount());
            if (ceiling.isEmpty()) {
                return AccessDecision.deny(operation,
                    "aucun plafond defini en " + target.amount().currency()
                    + " pour les roles " + caller.roles()
                    + " : le defaut est l'absence de droit, pas l'absence de limite");
            }
            if (target.amount().isGreaterThan(ceiling.get())) {
                return AccessDecision.deny(operation,
                    "montant " + target.amount() + " au-dela du plafond " + ceiling.get());
            }
        }

        // 5. Separation des taches.
        if (rule.dualControl() && target.ownerSubjectId() != null
            && target.ownerSubjectId().equals(caller.subjectId())) {
            return AccessDecision.deny(operation,
                "separation des taches : l'auteur d'une operation ne peut pas la valider");
        }

        return AccessDecision.allow(operation);
    }

    /**
     * Plafond le plus favorable parmi les roles de l'appelant, dans la devise du montant.
     *
     * <p>Un plafond exprime dans une autre devise n'est pas converti : le cours introduirait une
     * dependance a une donnee de marche dans une decision d'habilitation, et un plafond effectif
     * qui varie avec le change n'est pas un plafond. L'absence de plafond dans la devise concernee
     * vaut refus.
     */
    private Optional<Money> highestCeiling(Caller caller, AccessRule rule, Money amount) {
        return rule.ceilings().entrySet().stream()
            .filter(entry -> caller.roles().contains(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .filter(ceiling -> ceiling.currency().equals(amount.currency()))
            .max(Money::compareTo);
    }
}
