package io.corebanking.security;

/** Operation refusee par la politique d'habilitation. */
public class AccessDeniedException extends RuntimeException {

    private final transient AccessDecision decision;

    public AccessDeniedException(AccessDecision decision) {
        super("Operation " + decision.operation() + " refusee : " + decision.reason());
        this.decision = decision;
    }

    public AccessDecision decision() {
        return decision;
    }
}
