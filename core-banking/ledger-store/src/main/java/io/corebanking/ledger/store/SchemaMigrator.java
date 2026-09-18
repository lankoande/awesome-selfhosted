package io.corebanking.ledger.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * Montee de version du schema.
 *
 * <h2>Ce que cette classe est, et ce qu'elle n'est plus</h2>
 *
 * <p>C'est un adaptateur devant <b>Liquibase</b> : il resout la source de donnees, pose le
 * changelog maitre et rend un compte rendu lisible dans les journaux du demarrage. Les
 * garanties — ordre, somme de controle, verrou, atomicite — sont celles de Liquibase, plus
 * celles de PostgreSQL dont le DDL est transactionnel.
 *
 * <p>Jusqu'ici, ces garanties etaient <b>reimplementees ici</b> : table de versions, sommes de
 * controle, verrou consultatif, declaration des scripts par module. Cela marchait et c'etait
 * teste, mais tenir un moteur de migration n'est pas le metier de ce depot, et l'exploitation
 * n'avait aucun moyen de lire ce qui allait s'appliquer avant de l'appliquer.
 *
 * <h2>Les scripts</h2>
 *
 * <p>Ils vivent tous dans le module {@code schema-db}, dans l'ordre d'un seul changelog maitre.
 * Ils etaient repartis par module ; la numerotation, elle, a toujours ete globale — V9 (credits)
 * s'appuie sur V8 (commissions), qui s'appuie sur V1 (registre). Le decoupage etait une
 * apparence.
 *
 * <p>Chaque script reste du <b>SQL</b>, avec un en-tete Liquibase de deux lignes. Aucun n'est
 * decoupe en instructions ({@code splitStatements:false}) : c'est ce que faisait le runner
 * precedent — un seul {@code Statement.execute()} par fichier —, et seize de ces scripts
 * definissent des fonctions PL/pgSQL dont le corps contient des points-virgules.
 *
 * <h2>Ce qui n'a pas change</h2>
 *
 * <p>Le chemin de deploiement reste <b>exercé a chaque build</b> : chaque base de test est montee
 * par cette methode, celle-la meme qui monte la production. Un socle dont on ne verifie la montee
 * de version que le jour du deploiement n'est pas un socle.
 */
public final class SchemaMigrator {

    /** Le changelog maitre, livre par {@code schema-db}. */
    public static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    private SchemaMigrator() {
    }

    /** Ce que la montee de version a fait, pour le dire au journal de demarrage. */
    public record Report(List<String> applied, int alreadyApplied, String highest) {

        public Report {
            applied = List.copyOf(applied);
        }
    }

    // ------------------------------------------------------------------ montee de version

    /** Applique, en ordre, tout changement du changelog non encore applique. */
    public static Report migrate(Database database) {
        try (Connection connection = database.dataSource().getConnection()) {
            liquibase.database.Database cible = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), cible)) {
                // Lus avant l'application : apres, il n'y a plus rien a lister, et le
                // demarrage doit pouvoir dire ce qu'il vient de faire.
                List<ChangeSet> attente = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
                List<String> aAppliquer = attente.stream().map(ChangeSet::getId).toList();
                int deja = liquibase.getDatabaseChangeLog().getChangeSets().size() - aAppliquer.size();

                liquibase.update(new Contexts(), new LabelExpression());

                String plusHaute = liquibase.getDatabaseChangeLog().getChangeSets().stream()
                    .map(ChangeSet::getId).reduce((a, b) -> b).orElse("0");
                return new Report(aAppliquer, deja, plusHaute);
            }
        } catch (LiquibaseException e) {
            throw new MigrationException("montee de version refusee : " + e.getMessage(), e);
        } catch (SQLException e) {
            throw new MigrationException("montee de version impossible : " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ partitions

    /**
     * Garantit les partitions mensuelles des tables partitionnees sur l'intervalle donne.
     *
     * <p>Une partition manquante fait echouer toute comptabilisation sur la date concernee. C'est
     * volontaire — mieux vaut un refus explicite qu'une partition par defaut qui accueillerait
     * silencieusement des annees d'ecritures et rendrait l'archivage impossible. C'est le
     * traitement de fin de journee qui les anticipe, a chaque bascule.
     *
     * <p>Ce n'est pas une montee de version : les partitions naissent d'une date, pas d'un
     * changement de schema. Elles restent donc ici, hors du changelog.
     */
    public static void ensurePartitions(Database database, LocalDate from, LocalDate to) {
        database.inTransaction(connection -> {
            ensurePartitions(connection, from, to);
            return null;
        });
    }

    public static void ensurePartitions(Connection connection, LocalDate from, LocalDate to) {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT ledger_ensure_partitions(?, ?)")) {
            ps.setObject(1, from);
            ps.setObject(2, to);
            ps.execute();
        } catch (SQLException e) {
            throw new LedgerStoreException("Echec de creation des partitions", e);
        }
    }

    /** Montee de version refusee ou impossible. */
    public static class MigrationException extends LedgerStoreException {
        public MigrationException(String detail) {
            super("Schema : " + detail);
        }

        public MigrationException(String detail, Throwable cause) {
            super("Schema : " + detail, cause);
        }
    }
}
