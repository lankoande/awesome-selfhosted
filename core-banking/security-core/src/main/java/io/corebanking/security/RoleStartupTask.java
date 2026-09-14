package io.corebanking.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chargement du catalogue et provisionnement des roles au demarrage.
 *
 * <p>Ordre des controles, et il compte :
 *
 * <ol>
 *   <li><b>Coherence catalogue / politique.</b> Avant tout appel au fournisseur d'identite : un
 *       catalogue incoherent ne doit pas etre pousse dans le royaume, il doit empecher le
 *       demarrage.</li>
 *   <li><b>Creation des roles manquants</b>, puis mise a jour des roles systeme existants.</li>
 *   <li><b>Signalement des orphelins</b> — presents dans le royaume, absents du catalogue. Ils sont
 *       rapportes et laisses en place.</li>
 * </ol>
 *
 * <p>La tache est <b>idempotente</b> : la rejouer converge sans rien casser. C'est ce qui permet de
 * la lancer a chaque demarrage d'instance, y compris en montee de version progressive ou plusieurs
 * versions cohabitent quelques minutes.
 */
public final class RoleStartupTask {

    private final RoleProvisioner provisioner;

    public RoleStartupTask(RoleProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /**
     * Resultat du provisionnement.
     *
     * @param orphans roles presents dans le royaume et absents du catalogue. Jamais supprimes :
     *                leur suppression revoquerait instantanement tous leurs porteurs.
     */
    public record Report(List<String> created, List<String> updated, Set<String> orphans) {

        public Report {
            created = List.copyOf(created);
            updated = List.copyOf(updated);
            orphans = Set.copyOf(orphans);
        }

        public boolean hasOrphans() {
            return !orphans.isEmpty();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder("Provisionnement des roles : ")
                .append(created.size()).append(" cree(s), ")
                .append(updated.size()).append(" mis a jour.");
            if (hasOrphans()) {
                sb.append("\n  Roles presents dans le royaume et absents du catalogue : ")
                  .append(new TreeSet<>(orphans))
                  .append("\n    -> laisses en place. Les supprimer revoquerait instantanement "
                          + "leurs porteurs ; la decision appartient a la securite operationnelle.");
            }
            return sb.toString();
        }
    }

    public Report run() {
        // 1. Un catalogue incoherent empeche de servir, et n'est pas pousse dans le royaume.
        RoleCatalogue.validateAgainstPolicy();

        String clientId = RoleCatalogue.clientId();
        Set<String> existing = provisioner.existingClientRoles(clientId);

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (RoleDefinition role : RoleCatalogue.definitions().values()) {
            if (!existing.contains(role.code())) {
                provisioner.createRole(clientId, role);
                created.add(role.code());
            } else if (role.isSystem()) {
                // Les roles systeme convergent vers le catalogue a chaque demarrage. Un role non
                // systeme, une fois amorce, appartient a la banque : on n'ecrase pas ses libelles.
                provisioner.updateRole(clientId, role);
                updated.add(role.code());
            }
        }

        Set<String> orphans = new TreeSet<>(existing);
        orphans.removeAll(RoleCatalogue.declared());

        return new Report(created, updated, orphans);
    }
}
