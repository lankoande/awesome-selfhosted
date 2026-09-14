package io.corebanking.benchmark;

import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.RunMode;
import io.corebanking.tfj.StandardTfj;
import io.corebanking.tfj.TfjEngine;
import io.corebanking.tfj.TfjRun;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Duree d'un traitement de fin de journee.
 *
 * <p>Cible du dossier : deux millions de comptes en moins de quatre-vingt-dix minutes. Le banc
 * tourne sur un echantillon et extrapole — ce qui est legitime tant que le cout par compte est
 * constant, et ce que la mesure verifie.
 *
 * <pre>mvn test -pl benchmark -Dtest=TfjBenchmark -Dbench.accounts=2000</pre>
 */
class TfjBenchmark extends BenchmarkBase {

    private static final int TARGET_ACCOUNTS = 2_000_000;
    private static final int TARGET_MINUTES = 90;

    @Test
    @DisplayName("duree du TFJ et extrapolation a la volumetrie cible")
    void tfj_duration() {
        int accountCount = sizing("accounts", 2000);

        Account caisse = gl("GL-CAISSE", NormalBalance.DEBIT, 64);
        Account charges = gl("GL-CHARGES-INT", NormalBalance.DEBIT, 64);
        Account courus = gl("GL-COURUS", NormalBalance.CREDIT, 64);
        Account produitFrais = gl("GL-COMMISSIONS", NormalBalance.CREDIT, 64);
        Account taxeFrais = gl("GL-TOB", NormalBalance.CREDIT, 64);
        List<Account> clients = seedCustomers(accountCount, caisse, "10000000");

        // Cas defavorable assume : tous les comptes sont exigibles le meme jour. Une commission
        // debite un compte client different a chaque fois — elle ne s'agrege pas comme les
        // interets — et c'est donc elle qui dimensionne la fenetre de traitement.
        attachProduct(clients, charges, courus, java.util.Map.of(
            "fee.codes", "TENUE",
            "fee.TENUE.frequency", "MONTHLY",
            "fee.TENUE.anchor", DAY.toString(),
            "fee.TENUE.timing", "IN_ADVANCE",
            "fee.TENUE.basis", "FLAT",
            "fee.TENUE.amount", "2000",
            "fee.TENUE.tax_rate", "18",
            "fee.TENUE.income_account", produitFrais.id().toString(),
            "fee.TENUE.tax_account", taxeFrais.id().toString()));
        analyze();

        line("");
        line("=== Traitement de fin de journee ===");
        line("comptes remuneres et commissionnes : " + accountCount);

        TfjEngine engine = StandardTfj.engine(
            database, postingService, interestService,
            new io.corebanking.fee.service.FeeChargingService(database, postingService), calendar);

        long start = System.currentTimeMillis();
        TfjRun run = engine.run(ENTITY, DAY, ACTOR, RunMode.REAL);
        long elapsed = System.currentTimeMillis() - start;

        line("statut : " + run.status() + ", duree totale " + elapsed + " ms");
        printStepDurations(run);

        double perAccountMillis = elapsed * 1.0 / accountCount;
        double projectedMinutes = perAccountMillis * TARGET_ACCOUNTS / 60_000.0;

        line("");
        line(String.format("cout par compte      : %.3f ms", perAccountMillis));
        line(String.format("extrapolation %d M  : %.1f minutes (cible %d)",
                           TARGET_ACCOUNTS / 1_000_000, projectedMinutes, TARGET_MINUTES));
        line(projectedMinutes <= TARGET_MINUTES
             ? "=> la cible est tenue."
             : String.format("=> CIBLE MANQUEE d'un facteur %.1f.",
                             projectedMinutes / TARGET_MINUTES));

        org.assertj.core.api.Assertions.assertThat(run.isCompleted()).as(run.summary()).isTrue();
    }

    private void printStepDurations(TfjRun run) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                // EXTRACT(MILLISECONDS ...) inclut deja la part des secondes : y ajouter
                // 1000 * EXTRACT(SECONDS ...) double-compte. EPOCH donne la duree complete.
                "SELECT step_name, read_count, write_count,"
                + " EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000 AS duree"
                + " FROM batch_step WHERE run_id = ? ORDER BY step_order")) {
                ps.setObject(1, run.id());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        line(String.format("  %-20s lu %7d  ecrit %7d  %8.0f ms",
                                           rs.getString(1), rs.getLong(2), rs.getLong(3),
                                           rs.getDouble(4)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des durees d'etape", e);
            }
            return null;
        });
    }
}
