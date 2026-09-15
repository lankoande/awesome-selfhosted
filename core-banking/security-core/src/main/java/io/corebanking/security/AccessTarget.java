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
public record AccessTarget(UUID legalEntityId, UUID branchId, Money amount, String ownerSubjectId,
                           boolean remote) {

    public AccessTarget(UUID legalEntityId, UUID branchId, Money amount, String ownerSubjectId) {
        this(legalEntityId, branchId, amount, ownerSubjectId, false);
    }

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
        return new AccessTarget(legalEntityId, branchId, amount, ownerSubjectId, remote);
    }

    public AccessTarget madeBy(String subjectId) {
        return new AccessTarget(legalEntityId, branchId, amount, subjectId, remote);
    }

    /**
     * Titulaire de l'objet vise — la caisse d'un guichetier. Meme champ que l'auteur d'une
     * operation a valider : dans un cas la regle exige que l'appelant soit ce porteur, dans
     * l'autre qu'il ne le soit pas.
     */
    public AccessTarget ownedBy(String subjectId) {
        return new AccessTarget(legalEntityId, branchId, amount, subjectId, remote);
    }

    /**
     * Operation <b>deplacee</b> : realisee hors de l'agence gestionnaire de l'objet — le client
     * d'une autre agence servi a cette caisse, le compte d'une autre agence consulte. Elle n'est
     * possible que si la regle la prevoit, sous son propre plafond.
     */
    public AccessTarget performedRemotely() {
        return new AccessTarget(legalEntityId, branchId, amount, ownerSubjectId, true);
    }
}
