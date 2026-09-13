package io.corebanking.security;

import java.util.Objects;

/**
 * Point d'application unique des habilitations.
 *
 * <p>Un cas d'usage ne s'invoque que par ici. Il n'existe donc qu'<b>un seul</b> endroit dans tout
 * le systeme ou une habilitation est verifiee, et cet endroit est traverse par construction : un
 * appel qui l'evite n'est pas un controle oublie, c'est un cas d'usage inaccessible.
 *
 * <p>C'est la difference de fond avec les annotations. Une methode sans {@code @PreAuthorize} reste
 * appelable et s'execute sans controle — l'oubli est silencieux et se decouvre a l'audit, ou apres
 * l'incident. Ici, l'oubli possible n'est pas celui du controle mais celui de la <b>regle</b>, et
 * cet oubli-la empeche l'application de demarrer.
 */
public final class UseCaseExecutor {

    private final AuthorizationService authorization;

    public UseCaseExecutor(AuthorizationService authorization) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public <C, R> R run(Caller caller, UseCase<C, R> useCase, C command) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(useCase, "useCase");

        authorization.require(caller, useCase.operation(), useCase.targetOf(command));
        return useCase.execute(command);
    }
}
