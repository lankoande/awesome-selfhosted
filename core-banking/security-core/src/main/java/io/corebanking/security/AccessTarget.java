package io.corebanking.security;

import io.corebanking.kernel.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Objet sur lequel porte une operation.
 *
 * <p>L'entite juridique est obligatoire : c'est la dimension de cloisonnement la plus structurante,
 * et la laisser facultative reviendrait a autoriser des appels sans perimetre.
 *
 * @param branchId       agence de rattachement de l'objet, nulle si sans portee d'agence
 * @param amount         montant en jeu, nul si l'operation n'en porte pas
 * @param ownerSubjectId auteur de l'operation d'origine, pour le controle de separation des taches
 */
public record AccessTarget(UUID legalEntityId, UUID branchId, Money amount, String ownerSubjectId) {

    public AccessTarget {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
    }

    public static AccessTarget inEntity(UUID legalEntityId) {
        return new AccessTarget(legalEntityId, null, null, null);
    }

    public static AccessTarget inBranch(UUID legalEntityId, UUID branchId) {
        return new AccessTarget(legalEntityId, branchId, null, null);
    }

    public AccessTarget withAmount(Money amount) {
        return new AccessTarget(legalEntityId, branchId, amount, ownerSubjectId);
    }

    public AccessTarget madeBy(String subjectId) {
        return new AccessTarget(legalEntityId, branchId, amount, subjectId);
    }
}
