package io.corebanking.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Identite de l'appelant, telle qu'etablie par le fournisseur d'identite.
 *
 * <p><b>Le jeton dit qui vous etes et ou vous travaillez ; la politique dit ce que vous avez le
 * droit de faire.</b> Cette separation n'est pas cosmetique. Porter les plafonds d'operation dans
 * le jeton reviendrait a confier une decision d'habilitation a la configuration d'un annuaire : un
 * attribut mal renseigne dans Keycloak eleverait le plafond d'un guichetier sans qu'aucune revue
 * applicative ne le voie, et le plafond effectif serait introuvable ailleurs que dans un jeton
 * expire. Les plafonds vivent donc dans {@link SecurityConfig}, versionne et revu comme du code.
 *
 * @param subjectId      identifiant stable du porteur, revendication {@code sub}
 * @param username       identifiant de connexion, pour la piste d'audit
 * @param roles          roles applicatifs, issus de {@code realm_access.roles}
 * @param legalEntityId  entite juridique de rattachement
 * @param branchId       agence de rattachement, nulle pour un profil siege
 */
public record Caller(
    String subjectId,
    String username,
    Set<String> roles,
    UUID legalEntityId,
    UUID branchId) {

    public Caller {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(username, "username");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(legalEntityId,
            "legalEntityId : aucune identite n'est valable hors d'une entite juridique. "
            + "Un jeton sans entite est refuse plutot qu'interprete comme un acces global.");
    }

    public boolean hasAnyRole(Set<String> candidates) {
        return roles.stream().anyMatch(candidates::contains);
    }
}
