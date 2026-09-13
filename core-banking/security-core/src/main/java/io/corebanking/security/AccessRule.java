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
    boolean auditEvenOnSuccess) {

    public AccessRule {
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(scope, "scope");
        ceilings = Map.copyOf(Objects.requireNonNull(ceilings, "ceilings"));
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

        private Builder(Set<String> roles) {
            this.roles = roles;
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
            return new AccessRule(roles, scope, ceilings, dualControl, auditEvenOnSuccess);
        }
    }
}
