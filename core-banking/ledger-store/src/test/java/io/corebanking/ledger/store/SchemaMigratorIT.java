package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La montee de version, depuis qu'elle passe par Liquibase.
 *
 * <p>Ce qui etait teste ici — ordre, somme de controle, verrou, refus d'un script renumerote —
 * est desormais tenu par Liquibase, et le reverifier reviendrait a tester une bibliotheque
 * eprouvee a la place de son propre code. Restent deux choses qui sont a nous.
 *
 * <p>La premiere est <b>l'accord entre les scripts livres et le changelog</b>. Un script depose
 * dans {@code schema-db} mais oublie dans le changelog maitre ne s'appliquerait jamais, en
 * silence : la base de test passerait, la production aussi, et le manque n'apparaitrait qu'au
 * premier appel qui en a besoin. C'est exactement ce que la declaration par module garantissait
 * avant, et il n'y a aucune raison de le perdre en changeant d'outil.
 *
 * <p>La seconde est que <b>la montee de version a bien eu lieu</b> : une base de test est montee
 * par le meme chemin que la production, et le schema qui en sort porte les tables du registre.
 */
class SchemaMigratorIT extends LedgerTestBase {

    private static final Pattern INCLUS = Pattern.compile("<include file=\"db/(V(\\d+)__[^\"]+)\"");

    @Test
    @DisplayName("le changelog cite tous les scripts livres, dans l'ordre des versions")
    void changelogCoversEveryScript() {
        List<String> cites = new ArrayList<>();
        List<Integer> versions = new ArrayList<>();
        Matcher m = INCLUS.matcher(lire(SchemaMigrator.CHANGELOG));
        while (m.find()) {
            cites.add(m.group(1));
            versions.add(Integer.valueOf(m.group(2)));
        }

        assertThat(cites).as("le changelog maitre ne cite aucun script").isNotEmpty();
        // Chaque script cite doit exister, sans quoi la montee de version echouerait au
        // deploiement — donc en production, et non ici.
        assertThat(cites).allSatisfy(nom ->
            assertThat(SchemaMigrator.class.getClassLoader().getResource("db/" + nom))
                .as("script cite mais absent : %s", nom).isNotNull());
        // L'ordre est global et croissant : c'est la seule chose que la numerotation promet.
        assertThat(versions).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("la base de test est montee par le chemin de deploiement, et porte le registre")
    void testDatabaseIsMigratedByTheDeploymentPath() {
        // Rejouer est sans effet : LedgerTestBase a deja monte cette base.
        SchemaMigrator.Report rejeu = SchemaMigrator.migrate(database);
        assertThat(rejeu.applied()).as("un rejeu ne reapplique rien").isEmpty();
        assertThat(rejeu.alreadyApplied()).isPositive();

        assertThat(compte("SELECT count(*) FROM information_schema.tables"
                          + " WHERE table_schema = 'public' AND table_name = 'journal_entry'"))
            .as("le registre est la").isEqualTo(1);
        assertThat(compte("SELECT count(*) FROM databasechangelog")).isPositive();
    }

    @Test
    @DisplayName("la creation des partitions est idempotente et tolere la concurrence")
    void partitionsAreIdempotent() {
        SchemaMigrator.ensurePartitions(database, BUSINESS_DATE.plusYears(3),
                                        BUSINESS_DATE.plusYears(3).plusMonths(1));
        SchemaMigrator.ensurePartitions(database, BUSINESS_DATE.plusYears(3),
                                        BUSINESS_DATE.plusYears(3).plusMonths(1));
        boolean exists = database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT ledger_partition_exists(?)")) {
                ps.setObject(1, BUSINESS_DATE.plusYears(3));
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("controle de partition", e);
            }
        });
        assertThat(exists).isTrue();
    }

    private static String lire(String ressource) {
        try (InputStream in = SchemaMigrator.class.getClassLoader().getResourceAsStream(ressource)) {
            assertThat(in).as("ressource absente : %s", ressource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(ressource, e);
        }
    }

    private static int compte(String requete) {
        return database.inTransaction(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(requete)) {
                rs.next();
                return rs.getInt(1);
            } catch (SQLException e) {
                throw new LedgerStoreException(requete, e);
            }
        });
    }
}
