package io.corebanking.ledger.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

/**
 * Application du schema. En production, Liquibase pilote les montees de version ; ici le script
 * est applique tel quel, ce qui suffit au P0 et garde les tests sans dependance supplementaire.
 */
public final class SchemaMigrator {

    private static final String SCRIPT = "/db/V1__ledger_core.sql";

    private SchemaMigrator() {}

    public static void migrate(Database database) {
        String sql = readScript();
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("Echec de l'application du schema", e);
            }
        });
    }

    /**
     * Cree les partitions mensuelles du journal sur une plage de dates.
     *
     * <p>En production cette creation est anticipee par un job planifie : une partition manquante
     * fait echouer toute comptabilisation sur la date concernee. C'est volontaire — mieux vaut un
     * refus explicite qu'une partition par defaut qui accueillerait silencieusement des annees
     * d'ecritures et rendrait l'archivage impossible.
     */
    public static void ensurePartitions(Database database, LocalDate from, LocalDate to) {
        database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement("SELECT ledger_ensure_partitions(?, ?)")) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                ps.execute();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("Echec de creation des partitions", e);
            }
        });
    }

    private static String readScript() {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new LedgerStoreException("Script de schema introuvable : " + SCRIPT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LedgerStoreException("Lecture du script de schema impossible", e);
        }
    }
}
