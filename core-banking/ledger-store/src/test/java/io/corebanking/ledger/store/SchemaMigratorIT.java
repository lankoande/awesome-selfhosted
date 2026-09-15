package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le runner de migrations, sur le classpath de ce module : V1 et V16, rien entre les deux.
 *
 * <p>Le trou est voulu — les scripts intermediaires appartiennent aux autres modules — et c'est
 * ce qui permet d'eprouver ici la difference entre un deploiement, qui le refuse, et les tests
 * d'un module, qui le tolerent.
 */
class SchemaMigratorIT extends LedgerTestBase {

    @Test
    @DisplayName("les scripts declares sont decouverts avec leur version et leur somme de controle")
    void discovery() {
        Map<Integer, SchemaMigrator.Migration> found = SchemaMigrator.discover();
        assertThat(found).containsKeys(1, 16);
        assertThat(found.get(1).description()).isEqualTo("ledger_core");
        assertThat(found.get(1).checksum()).hasSize(64);
        assertThat(found.get(16).sql()).contains("pg_advisory_xact_lock");
    }

    @Test
    @DisplayName("un deploiement refuse un classpath a trous ; les tests d'un module le tolerent")
    void gaps() {
        // Un module absent du deploiement se voit ici, pas au premier appel qui le demande.
        assertThatThrownBy(() -> SchemaMigrator.migrate(database))
            .isInstanceOf(SchemaMigrator.MigrationException.class)
            .hasMessageContaining("versions absentes du classpath")
            .hasMessageContaining("[2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 19, 20, 22, 23, 25, 27, 28, 29, 30, 31, 32, 33, 34, 35]");

        SchemaMigrator.Report report = SchemaMigrator.migrate(database,
                                                              SchemaMigrator.Gaps.TOLERATED);
        assertThat(report.applied()).isEmpty();        // la base de test est deja a niveau
        assertThat(report.alreadyApplied()).isEqualTo(8);
        assertThat(report.highest()).isEqualTo(37);
    }

    @Test
    @DisplayName("un script modifie apres son application est refuse a la montee suivante")
    void modifiedScriptIsRefused() {
        String original = SchemaMigrator.discover().get(16).checksum();
        setChecksum(16, "0000");
        try {
            assertThatThrownBy(() -> SchemaMigrator.migrate(database,
                                                            SchemaMigrator.Gaps.TOLERATED))
                .isInstanceOf(SchemaMigrator.MigrationException.class)
                .hasMessageContaining("V16")
                .hasMessageContaining("modifie apres son application");
        } finally {
            setChecksum(16, original);
        }
    }

    @Test
    @DisplayName("une version en base sans script sur le classpath signale un module disparu")
    void vanishedModuleIsRefused() {
        sql("INSERT INTO schema_version(version, description, checksum, duration_ms)"
            + " VALUES (99, 'fantome', 'x', 0)");
        try {
            assertThatThrownBy(() -> SchemaMigrator.migrate(database,
                                                            SchemaMigrator.Gaps.TOLERATED))
                .isInstanceOf(SchemaMigrator.MigrationException.class)
                .hasMessageContaining("V99")
                .hasMessageContaining("un module a disparu du deploiement");
        } finally {
            sql("DELETE FROM schema_version WHERE version = 99");
        }
    }

    @Test
    @DisplayName("un script decouvert sous une version deja depassee est refuse : l'ordre est rompu")
    void outOfOrderScriptIsRefused() {
        // V1 est retire du registre alors que la base est en V16 : le runner voudrait appliquer
        // un script anterieur a l'etat courant. C'est la signature d'un script renumerote ou d'un
        // module ajoute apres coup — et rien n'est execute, le refus precede tout.
        String checksum = SchemaMigrator.discover().get(1).checksum();
        sql("DELETE FROM schema_version WHERE version = 1");
        try {
            assertThatThrownBy(() -> SchemaMigrator.migrate(database,
                                                            SchemaMigrator.Gaps.TOLERATED))
                .isInstanceOf(SchemaMigrator.MigrationException.class)
                .hasMessageContaining("ordre rompu")
                .hasMessageContaining("V1 ");
        } finally {
            sql("INSERT INTO schema_version(version, description, checksum, duration_ms)"
                + " VALUES (1, 'ledger_core', '" + checksum + "', 0)");
        }
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

    private static void setChecksum(int version, String checksum) {
        sql("UPDATE schema_version SET checksum = '" + checksum + "' WHERE version = " + version);
    }

    private static void sql(String statement) {
        database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.execute(statement);
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException(statement, e);
            }
        });
    }
}
