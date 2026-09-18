package io.corebanking.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le schema tel qu'il sera deploye : ce module porte toutes les migrations.
 *
 * <p>Deux invariants que rien d'autre ne verifie. Le premier : aucune version ne manque au
 * classpath du deploiement — un module oublie se verrait ici, pas au premier appel qui le
 * demande. Le second : toute table porte une politique de securite au niveau des lignes, sauf
 * celles qui sont nommees ci-dessous. Une table ajoutee sans politique est visible d'une entite a
 * l'autre ; la liste des exceptions doit se lire, se justifier et grandir a regret.
 */
class SchemaInvariantsIT {

    private static EmbeddedPostgres postgres;
    private static Database database;

    /**
     * Les tables sans politique, inventaire tenu a jour et relu.
     *
     * <p>Trois familles, et rien d'autre : l'outillage du schema, qui ne porte aucune donnee de
     * client ; le referentiel partage par toutes les entites — devises, calendriers — ; et les
     * tables tenues par une mere elle-meme cloisonnee, qu'on n'atteint jamais sans elle : le
     * compte, le contrat de credit, la maquette, le profil de risque, la version de produit. Les
     * soldes sont de celles-la, et sont en outre sur le chemin chaud de l'imputation.
     *
     * <p>Une table qui arrive ici sans y avoir ete mise sciemment est une fuite d'une entite a
     * l'autre : la liste doit se lire, se justifier, et se reduire.
     */
    private static final Set<String> SANS_POLITIQUE = Set.of(
        // Outillage du schema. Les deux tables de Liquibase tiennent le journal des
        // montees de version : elles ne portent aucune donnee d'entite, et la
        // cloisonner empecherait le role de migration de relire ce qu'il a applique.
        "databasechangelog", "databasechangeloglock", "ledger_partitioned_table",
        // Referentiel partage entre entites.
        "currency", "business_calendar", "calendar_holiday", "calendar_weekend",
        // Tenues par le compte.
        "account_balance", "account_balance_daily", "branch_balance_daily", "overdraft_limit",
        "interest_accrual", "interest_position", "interest_settlement",
        // Tenues par l'ecriture ou par l'agence.
        "journal_reversal", "branch_liaison",
        // Tenues par le contrat de credit ou sa surete.
        "collateral_allocation", "loan_classification", "loan_interest_accrual",
        "loan_interim_interest", "loan_late_accrual", "loan_mobilisation",
        "loan_payment_allocation", "loan_schedule_line", "loan_tranche",
        // Tenues par la version de produit, le schema comptable ou le profil de risque.
        "product_audit", "product_rate_tier", "accounting_schema_derivation",
        "accounting_schema_line", "risk_bucket");

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 2);
        // Sans tolerance : le classpath du deploiement doit etre complet.
        SchemaMigrator.migrate(database);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("toute table du schema porte une politique de securite au niveau des lignes, hors exceptions nommees")
    void every_table_is_isolated_by_entity() {
        List<String> nues = database.inTransaction(c -> {
            List<String> found = new ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'public' AND c.relkind IN ('r','p')"
                + "   AND NOT c.relrowsecurity"
                + "   AND c.relispartition = false"
                + " ORDER BY c.relname");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Lecture des tables du schema", e);
            }
            return found;
        });
        assertThat(nues).as("tables sans politique de securite au niveau des lignes")
            .containsExactlyInAnyOrderElementsOf(SANS_POLITIQUE);
    }
}
