package io.corebanking.security;

import io.corebanking.kernel.money.Money;
import java.util.Map;
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

        // 3. Perimetre d'agence. Une operation deplacee — l'objet d'une autre agence — n'est
        //    admise que si la regle la prevoit ; elle porte alors son propre plafond.
        boolean remote = rule.scope() == Scope.OWN_BRANCH && target.remote();
        if (rule.scope() == Scope.OWN_BRANCH) {
            if (caller.branchId() == null) {
                return AccessDecision.deny(operation,
                    "operation limitee au perimetre d'agence, appelant sans agence de rattachement");
            }
            if (remote && !rule.remoteAllowed()) {
                return AccessDecision.deny(operation,
                    "operation deplacee : cette operation ne se fait que dans l'agence "
                    + "gestionnaire de l'objet");
            }
            if (target.branchId() != null && !caller.branchId().equals(target.branchId())
                && !remote) {
                return AccessDecision.deny(operation,
                    "objet rattache a l'agence " + target.branchId()
                    + ", appelant rattache a " + caller.branchId());
            }
        }

        // 4. Plafond de montant : celui des operations deplacees quand il en existe un.
        Map<String, Money> ceilings = remote && !rule.remoteCeilings().isEmpty()
            ? rule.remoteCeilings() : rule.ceilings();
        if (target.amount() != null && !ceilings.isEmpty()) {
            Optional<Money> ceiling = highestCeiling(caller, ceilings, target.amount());
            if (ceiling.isEmpty()) {
                return AccessDecision.deny(operation,
                    "aucun plafond defini en " + target.amount().currency()
                    + " pour les roles " + caller.roles()
                    + (remote ? " en operation deplacee" : "")
                    + " : le defaut est l'absence de droit, pas l'absence de limite");
            }
            if (target.amount().isGreaterThan(ceiling.get())) {
                return AccessDecision.deny(operation,
                    "montant " + target.amount() + " au-dela du plafond " + ceiling.get()
                    + (remote ? " des operations deplacees" : ""));
            }
        }

        // 5. Separation des taches.
        if (rule.dualControl() && target.ownerSubjectId() != null
            && target.ownerSubjectId().equals(caller.subjectId())) {
            return AccessDecision.deny(operation,
                "separation des taches : l'auteur d'une operation ne peut pas la valider");
        }

        // 6. Objet propre : un guichetier n'arrete que sa caisse. Le titulaire vient de l'objet,
        //    jamais de la requete ; un objet sans titulaire n'est celui de personne.
        if (rule.restrictedToOwnObjects(caller.roles())
            && !caller.subjectId().equals(target.ownerSubjectId())) {
            return AccessDecision.deny(operation,
                "objet propre : les roles " + caller.roles() + " n'agissent que sur leurs "
                + "propres objets, celui-ci est "
                + (target.ownerSubjectId() == null ? "sans titulaire"
                                                    : "celui de " + target.ownerSubjectId()));
        }

        return AccessDecision.allow(operation);
    }

    /**
     * Ce que les roles de l'appelant admettent, operation par operation.
     *
     * <p><b>Ce n'est pas une decision d'acces.</b> L'agence, le montant et l'objet vise ne sont
     * pas connus ici : seules les regles de role sont evaluees. Une operation presente dans cette
     * liste peut parfaitement etre refusee au moment d'agir — plafond depasse, objet d'une autre
     * agence, auteur qui voudrait valider sa propre ecriture. {@link #require} reste le seul
     * controle d'acces ; ceci sert a ne pas proposer une porte qu'on sait fermee.
     *
     * <p>La liste ne passe par aucun journal d'audit : lire ses propres droits n'est pas un acces
     * a une donnee clientele, et tracer chaque ouverture d'ecran noierait les traces qui comptent.
     */
    public java.util.List<Grant> grants(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        return java.util.Arrays.stream(Operation.values())
            .filter(operation -> caller.hasAnyRole(SecurityConfig.ruleFor(operation).roles()))
            .map(operation -> grantOf(caller, operation))
            .toList();
    }

    private static Grant grantOf(Caller caller, Operation operation) {
        AccessRule rule = SecurityConfig.ruleFor(operation);
        return new Grant(operation, rule.scope(), rule.dualControl(), rule.remoteAllowed(),
                         bestCeilings(caller, rule.ceilings()),
                         bestCeilings(caller, rule.remoteCeilings()));
    }

    /**
     * Le plafond le plus favorable de l'appelant, par devise. Une regle qui porte des plafonds
     * sans qu'aucun role de l'appelant n'en ait rend une carte vide : l'operation lui est alors
     * refusee des qu'un montant est en jeu, le defaut etant l'absence de droit.
     */
    private static Map<String, Money> bestCeilings(Caller caller, Map<String, Money> ceilings) {
        Map<String, Money> parDevise = new java.util.TreeMap<>();
        for (Map.Entry<String, Money> entry : ceilings.entrySet()) {
            if (!caller.roles().contains(entry.getKey())) {
                continue;
            }
            Money plafond = entry.getValue();
            parDevise.merge(plafond.currency().code(), plafond,
                            (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        return Map.copyOf(parDevise);
    }

    /**
     * Une operation que les roles de l'appelant admettent, et ce que la politique en dit.
     *
     * @param operation      operation du catalogue
     * @param scope          perimetre : entite, agence, ou toute entite
     * @param dualControl    l'operation exige un second regard, distinct de l'auteur
     * @param remoteAllowed  l'operation peut se faire hors de l'agence gestionnaire de l'objet
     * @param ceilings       plafond par devise, le plus favorable des roles de l'appelant ; vide
     *                       quand la regle n'en porte pas, ou qu'aucun role de l'appelant n'en a
     * @param remoteCeilings plafond par devise pour une operation deplacee, quand il differe
     */
    public record Grant(Operation operation, Scope scope, boolean dualControl,
                        boolean remoteAllowed, Map<String, Money> ceilings,
                        Map<String, Money> remoteCeilings) {}

    /**
     * Plafond le plus favorable parmi les roles de l'appelant, dans la devise du montant.
     *
     * <p>Un plafond exprime dans une autre devise n'est pas converti : le cours introduirait une
     * dependance a une donnee de marche dans une decision d'habilitation, et un plafond effectif
     * qui varie avec le change n'est pas un plafond. L'absence de plafond dans la devise concernee
     * vaut refus.
     */
    private Optional<Money> highestCeiling(Caller caller, Map<String, Money> ceilings,
                                           Money amount) {
        return ceilings.entrySet().stream()
            .filter(entry -> caller.roles().contains(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .filter(ceiling -> ceiling.currency().equals(amount.currency()))
            .max(Money::compareTo);
    }
}
