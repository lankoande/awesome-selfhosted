package io.corebanking.security;

import java.util.Set;

/**
 * Acces au fournisseur d'identite pour la creation des roles.
 *
 * <p>Interface volontairement etroite : lire les roles existants, en creer, en mettre a jour.
 * <b>Aucune suppression.</b> Supprimer un role dans Keycloak le revoque instantanement a tous ses
 * porteurs ; l'operation est irreversible dans la pratique, puisqu'elle perd aussi les affectations.
 * Un role disparu du catalogue est donc signale, jamais supprime : c'est une decision humaine.
 */
public interface RoleProvisioner {

    /** Roles du client backend actuellement declares dans le royaume. */
    Set<String> existingClientRoles(String clientId);

    /** Cree un role. L'appel n'est emis que pour un role absent du royaume. */
    void createRole(String clientId, RoleDefinition role);

    /**
     * Met a jour libelle, description et attributs d'un role existant.
     *
     * <p>Emis a chaque demarrage pour les roles systeme : c'est ce qui fait converger le royaume
     * vers le catalogue apres une modification du fichier, sans intervention manuelle.
     */
    void updateRole(String clientId, RoleDefinition role);
}
