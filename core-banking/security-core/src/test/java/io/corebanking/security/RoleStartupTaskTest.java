package io.corebanking.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoleStartupTaskTest {

    /** Royaume en memoire : le fournisseur d'identite reduit a ce que la tache en attend. */
    private static final class FakeRealm implements RoleProvisioner {
        private final Set<String> roles = new LinkedHashSet<>();
        private final List<String> created = new ArrayList<>();
        private final List<String> updated = new ArrayList<>();

        FakeRealm(String... existing) {
            roles.addAll(List.of(existing));
        }

        @Override public Set<String> existingClientRoles(String clientId) {
            return new TreeSet<>(roles);
        }

        @Override public void createRole(String clientId, RoleDefinition role) {
            roles.add(role.code());
            created.add(role.code());
        }

        @Override public void updateRole(String clientId, RoleDefinition role) {
            updated.add(role.code());
        }
    }

    @Test
    @DisplayName("au premier demarrage, tous les roles du catalogue sont crees")
    void a_first_start_creates_every_catalogue_role() {
        FakeRealm realm = new FakeRealm();

        var report = new RoleStartupTask(realm).run();

        assertThat(report.created()).containsExactlyInAnyOrderElementsOf(RoleCatalogue.declared());
        assertThat(report.updated()).isEmpty();
        assertThat(report.hasOrphans()).isFalse();
    }

    @Test
    @DisplayName("la tache est idempotente : un second demarrage ne cree rien et fait converger")
    void the_task_is_idempotent() {
        FakeRealm realm = new FakeRealm();
        new RoleStartupTask(realm).run();

        var second = new RoleStartupTask(realm).run();

        assertThat(second.created()).isEmpty();
        // Les roles systeme sont republies : c'est ce qui propage une modification du fichier
        // sans intervention manuelle dans la console.
        assertThat(second.updated()).containsExactlyInAnyOrderElementsOf(RoleCatalogue.declared());
    }

    @Test
    @DisplayName("un role ajoute au catalogue est cree au demarrage suivant, les autres intacts")
    void a_new_catalogue_role_is_created_on_next_start() {
        Set<String> presque = new TreeSet<>(RoleCatalogue.declared());
        presque.remove(Roles.OPERATOR);
        FakeRealm realm = new FakeRealm(presque.toArray(String[]::new));

        var report = new RoleStartupTask(realm).run();

        assertThat(report.created()).containsExactly(Roles.OPERATOR);
    }

    @Test
    @DisplayName("un role du royaume absent du catalogue est signale, jamais supprime")
    void an_orphan_realm_role_is_reported_never_deleted() {
        Set<String> avecIntrus = new TreeSet<>(RoleCatalogue.declared());
        avecIntrus.add("super_admin");
        FakeRealm realm = new FakeRealm(avecIntrus.toArray(String[]::new));

        var report = new RoleStartupTask(realm).run();

        assertThat(report.orphans()).containsExactly("super_admin");
        // Supprimer un role revoque instantanement tous ses porteurs : la tache ne le fait pas.
        assertThat(realm.roles).contains("super_admin");
        assertThat(report.summary()).contains("revoquerait instantanement");
    }
}
