package io.corebanking.security;

/**
 * Decision d'habilitation.
 *
 * <p>Un refus porte toujours un motif exploitable. Un message generique de type « acces refuse »
 * transforme chaque incident d'habilitation en investigation, et pousse les equipes d'exploitation
 * a elargir les roles au hasard jusqu'a ce que l'appel passe.
 */
public record AccessDecision(boolean allowed, Operation operation, String reason) {

    public static AccessDecision allow(Operation operation) {
        return new AccessDecision(true, operation, null);
    }

    public static AccessDecision deny(Operation operation, String reason) {
        return new AccessDecision(false, operation, reason);
    }
}
