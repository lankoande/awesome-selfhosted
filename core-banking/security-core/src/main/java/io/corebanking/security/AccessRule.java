package io.corebanking.security;

import io.corebanking.kernel.money.Money;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Regle d'habilitation d'une operation.
 *
 * @param roles              roles autorises ; un ensemble vide interdit l'operation a tous
 * @param scope              perimetre autorise
 * @param ceilings           plafond de montant par role. Vide : aucun plafond applicable. Renseigne :
 *                           un appelant dont aucun role ne porte de plafond est refuse — le defaut
 *                           est l'absence de droit, pas l'absence de limite.
 * @param dualControl        l'operation exige que le valideur soit distinct de l'auteur
 * @param auditEvenOnSuccess trace aussi les acces reussis. Active sur les consultations de donnees
 *                           clientele : la consultation abusive par un agent habilite est la forme
 *                           de fraude interne la plus courante, et elle est invisible d'un journal
 *                           qui n'enregistre que les modifications.
 */
public record AccessRule(
    Set<String> roles,
    Scope scope,
    Map<String, Money> ceilings,
    boolean dualControl,
    boolean auditEvenOnSuccess,
    boolean remoteAllowed,
    Map<String, Money> remoteCeilings,
    Set<String> ownOnlyRoles) {

    public AccessRule(Set<String> roles, Scope scope, Map<String, Money> ceilings,
                      boolean dualControl, boolean auditEvenOnSuccess) {
        this(roles, scope, ceilings, dualControl, auditEvenOnSuccess, false, Map.of(), Set.of());
    }

    public AccessRule(Set<String> roles, Scope scope, Map<String, Money> ceilings,
                      boolean dualControl, boolean auditEvenOnSuccess, boolean remoteAllowed,
                      Map<String, Money> remoteCeilings) {
        this(roles, scope, ceilings, dualControl, auditEvenOnSuccess, remoteAllowed,
             remoteCeilings, Set.of());
    }

    public AccessRule {
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(scope, "scope");
        ceilings = Map.copyOf(Objects.requireNonNull(ceilings, "ceilings"));
        remoteCeilings = Map.copyOf(remoteCeilings == null ? Map.of() : remoteCeilings);
        ownOnlyRoles = Set.copyOf(ownOnlyRoles == null ? Set.of() : ownOnlyRoles);
        if (!remoteCeilings.isEmpty() && !remoteAllowed) {
            throw new IllegalArgumentException(
                "Un plafond deplace sans operation deplacee autorisee n'a pas de sens");
        }
        if (!roles.containsAll(ownOnlyRoles)) {
            throw new IllegalArgumentException(
                "Un role limite a ses propres objets doit d'abord etre autorise : "
                + ownOnlyRoles + " hors de " + roles);
        }
    }

    /**
     * Vrai si l'appelant, avec ces roles, n'agit que sur ses propres objets : tous ses roles
     * admis par la regle y sont limites. Un role plus large — le chef d'agence — leve la limite.
     */
    public boolean restrictedToOwnObjects(Set<String> callerRoles) {
        if (ownOnlyRoles.isEmpty()) {
            return false;
        }
        boolean admitted = false;
        for (String role : callerRoles) {
            if (roles.contains(role)) {
                admitted = true;
                if (!ownOnlyRoles.contains(role)) {
                    return false;
                }
            }
        }
        return admitted;
    }

    public static Builder allow(String... roles) {
        return new Builder(Set.of(roles));
    }

    /** Operation fermee a tous : aucune habilitation ne l'ouvre. */
    public static AccessRule denyAll() {
        return new AccessRule(Set.of(), Scope.OWN_ENTITY, Map.of(), false, false);
    }

    public static final class Builder {
        private final Set<String> roles;
        private Scope scope = Scope.OWN_ENTITY;
        private Map<String, Money> ceilings = Map.of();
        private boolean dualControl;
        private boolean auditEvenOnSuccess;
        private boolean remoteAllowed;
        private Map<String, Money> remoteCeilings = Map.of();
        private Set<String> ownOnlyRoles = Set.of();

        private Builder(Set<String> roles) {
            this.roles = roles;
        }

        /**
         * Ces roles n'agissent que sur leurs propres objets — la caisse dont ils sont titulaires.
         * L'objet doit nommer son titulaire ({@link AccessTarget#ownedBy}) ; un objet sans
         * titulaire leur est refuse.
         */
        public Builder ownOnlyFor(String... roles) {
            this.ownOnlyRoles = Set.of(roles);
            return this;
        }

        /**
         * L'operation peut etre deplacee : realisee hors de l'agence gestionnaire de l'objet.
         * Sans plafond propre, le plafond ordinaire s'applique.
         */
        public Builder allowingRemote() {
            this.remoteAllowed = true;
            return this;
        }

        /** L'operation peut etre deplacee, sous un plafond plus bas que l'ordinaire. */
        public Builder remoteUpTo(Map<String, Money> ceilingsByRole) {
            this.remoteAllowed = true;
            this.remoteCeilings = ceilingsByRole;
            return this;
        }

        public Builder within(Scope scope) {
            this.scope = scope;
            return this;
        }

        public Builder upTo(Map<String, Money> ceilingsByRole) {
            this.ceilings = ceilingsByRole;
            return this;
        }

        public Builder requiringSecondPerson() {
            this.dualControl = true;
            return this;
        }

        public Builder tracedOnRead() {
            this.auditEvenOnSuccess = true;
            return this;
        }

        public AccessRule build() {
            return new AccessRule(roles, scope, ceilings, dualControl, auditEvenOnSuccess,
                                  remoteAllowed, remoteCeilings, ownOnlyRoles);
        }
    }
}
