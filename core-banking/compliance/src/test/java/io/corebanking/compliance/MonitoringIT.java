package io.corebanking.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.domain.account.Account;
import io.corebanking.party.RiskRating;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La surveillance : ce que les scenarios voient, ce qu'ils ne voient pas, et ce qu'ils ne
 * reclament pas deux fois.
 */
class MonitoringIT extends ComplianceTestBase {

    private MonitoringService service() {
        return new MonitoringService(database);
    }

    @Test
    @DisplayName("les especes cumulees au-dela du seuil levent une alerte, une seule fois par fenetre, et seulement si la journee a contribue")
    void cash_above_the_threshold_raises_one_alert_per_window() {
        UUID client = client("LCB-CASH", RiskRating.MEDIUM);
        Account compte = compte("CLI-LCB-CASH", client);
        declarer("SEUIL-ESPECES", MonitoringScenarios.Method.CASH_THRESHOLD,
                 new BigDecimal("5000000"), 30, null, null, null);

        // Trois versements sur la fenetre : 2 + 2 + 2 millions. Le seuil est franchi le
        // troisieme jour, pas avant.
        especes(compte, "2000000", J.minusDays(5), "cash-1");
        especes(compte, "2000000", J.minusDays(3), "cash-2");
        dater(J.minusDays(3));
        MonitoringService.Result avant = service().run(ENTITY, J.minusDays(3), run(), ACTOR);
        assertThat(avant.alerts()).as("4 millions ne franchissent pas 5").isZero();

        especes(compte, "2000000", J, "cash-3");
        dater(J);
        MonitoringService.Result passe = service().run(ENTITY, J, run(), ACTOR);
        assertThat(passe.scenarios()).isEqualTo(1);
        assertThat(passe.alerts()).isEqualTo(1);

        List<AmlAlerts.Alert> alertes = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, "OPEN"));
        assertThat(alertes).hasSize(1);
        AmlAlerts.Alert alerte = alertes.getFirst();
        assertThat(alerte.partyId()).isEqualTo(client);
        assertThat(alerte.scenarioCode()).isEqualTo("SEUIL-ESPECES");
        assertThat(alerte.origin()).isEqualTo(AmlAlerts.Origin.MONITORING);
        assertThat(alerte.amount()).isEqualTo(xof("6000000"));
        assertThat(alerte.detail()).contains("6000000");
        // L'alerte porte ses pieces : sans elles, elle ne s'instruit pas.
        assertThat(alerte.items()).hasSize(3);
        assertThat(alerte.items()).allMatch(i -> i.accountId().equals(compte.id()));

        // Le lendemain, le cumul depasse toujours : la conformite ne doit pas recevoir le meme
        // fait une seconde fois.
        especes(compte, "100000", J.plusDays(1), "cash-4");
        dater(J.plusDays(1));
        assertThat(service().run(ENTITY, J.plusDays(1), run(), ACTOR).alerts())
            .as("une alerte deja levee sur la fenetre suffit").isZero();
    }

    @Test
    @DisplayName("le fractionnement se lit sur la suite, pas sur l'operation : chacune sous le seuil, leur somme au-dela")
    void structuring_reads_the_sequence_not_the_operation() {
        UUID client = client("LCB-FRAC", RiskRating.MEDIUM);
        Account compte = compte("CLI-LCB-FRAC", client);
        declarer("FRACTIONNEMENT", MonitoringScenarios.Method.STRUCTURING,
                 new BigDecimal("1000000"), 10, 3, null, null);

        // Quatre versements de 900 000 : aucun n'est anormal, leur suite l'est.
        especes(compte, "900000", J.minusDays(3), "frac-1");
        especes(compte, "900000", J.minusDays(2), "frac-2");
        especes(compte, "900000", J.minusDays(1), "frac-3");
        dater(J.minusDays(1));
        assertThat(service().run(ENTITY, J.minusDays(1), run(), ACTOR).alerts())
            .as("2 700 000 depassent deja 1 000 000 des la troisieme").isEqualTo(1);

        List<AmlAlerts.Alert> alertes = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null));
        AmlAlerts.Alert alerte = alertes.stream()
            .filter(a -> "FRACTIONNEMENT".equals(a.scenarioCode())).findFirst().orElseThrow();
        assertThat(alerte.detail()).contains("toutes inferieures a 1000000");
        assertThat(alerte.amount()).isEqualTo(xof("2700000"));

        // Une operation unique au-dessus du seuil n'est pas du fractionnement : le scenario ne
        // la compte pas.
        UUID autre = client("LCB-FRANC", RiskRating.MEDIUM);
        Account direct = compte("CLI-LCB-FRANC", autre);
        especes(direct, "5000000", J, "frac-direct");
        dater(J);
        List<AmlAlerts.Alert> apres = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null));
        service().run(ENTITY, J, run(), ACTOR);
        List<AmlAlerts.Alert> maintenant = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null));
        assertThat(maintenant).as("une seule grosse operation n'est pas un fractionnement")
            .hasSize(apres.size());
    }

    @Test
    @DisplayName("l'atypie se mesure contre le profil declare, et un client sans profil declare n'est pas suspect d'exister")
    void atypical_activity_is_measured_against_the_declared_profile() {
        UUID declare = client("LCB-PROFIL", RiskRating.MEDIUM);
        Account avecProfil = compte("CLI-LCB-PROFIL", declare);
        UUID sansProfil = client("LCB-SANS", RiskRating.MEDIUM);
        Account autre = compte("CLI-LCB-SANS", sansProfil);
        database.inTransaction(c -> {
            ActivityProfiles.declare(c, declare, xof("1000000"), xof("800000"), J.minusMonths(1),
                                     ACTOR);
            return null;
        });
        // Trois fois le flux mensuel declare, sur une fenetre de trente jours.
        declarer("ATYPIE", MonitoringScenarios.Method.ATYPICAL_ACTIVITY, null, 30, null,
                 new BigDecimal("3"), null);

        virementRecu(avecProfil, "3500000", J, "atyp-1");
        virementRecu(autre, "9000000", J, "atyp-2");
        dater(J);
        MonitoringService.Result resultat = service().run(ENTITY, J, run(), ACTOR);
        assertThat(resultat.alerts()).as("seul celui qui a declare un profil est compare")
            .isEqualTo(1);

        AmlAlerts.Alert alerte = database.inTransaction(c -> AmlAlerts.alerts(c, ENTITY, "OPEN"))
            .stream().filter(a -> "ATYPIE".equals(a.scenarioCode())).findFirst().orElseThrow();
        assertThat(alerte.partyId()).isEqualTo(declare);
        assertThat(alerte.detail()).contains("profil declare");
    }

    @Test
    @DisplayName("un scenario vise une population : le seuil des dossiers renforces ne noie pas la banque entiere")
    void a_scenario_targets_a_population() {
        UUID eleve = client("LCB-HAUT", RiskRating.HIGH);
        UUID faible = client("LCB-BAS", RiskRating.LOW);
        Account compteEleve = compte("CLI-LCB-HAUT", eleve);
        Account compteFaible = compte("CLI-LCB-BAS", faible);
        declarer("ESPECES-RENFORCE", MonitoringScenarios.Method.CASH_THRESHOLD,
                 new BigDecimal("500000"), 30, null, null, "HIGH");

        especes(compteEleve, "600000", J, "pop-1");
        especes(compteFaible, "600000", J, "pop-2");
        dater(J);
        service().run(ENTITY, J, run(), ACTOR);

        List<AmlAlerts.Alert> alertes = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null)).stream()
            .filter(a -> "ESPECES-RENFORCE".equals(a.scenarioCode())).toList();
        assertThat(alertes).hasSize(1);
        assertThat(alertes.getFirst().partyId()).isEqualTo(eleve);
    }

    @Test
    @DisplayName("un scenario incomplet est refuse a la declaration, et se declare a deux")
    void an_incomplete_scenario_is_refused() {
        // Un seuil sans fenetre ne surveille rien, et personne ne s'en apercoit.
        assertThatThrownBy(() -> new MonitoringScenarios.Draft(
                ENTITY, "INCOMPLET", "Sans fenetre", MonitoringScenarios.Method.CASH_THRESHOLD,
                new BigDecimal("1000000"), null, null, null, null, J, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parametres");

        assertThatThrownBy(() -> new MonitoringScenarios.Draft(
                ENTITY, "SEUL", "Decide seul", MonitoringScenarios.Method.CASH_THRESHOLD,
                new BigDecimal("1000000"), 30, null, null, null, J, null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        // Un fractionnement d'une seule operation n'est pas un fractionnement.
        assertThatThrownBy(() -> new MonitoringScenarios.Draft(
                ENTITY, "UN", "Une seule", MonitoringScenarios.Method.STRUCTURING,
                new BigDecimal("1000000"), 30, 1, null, null, J, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deux operations");
    }

    // ------------------------------------------------------------------ outillage

    private static UUID run() {
        return UUID.randomUUID();
    }

    private static void declarer(String code, MonitoringScenarios.Method method,
                                 BigDecimal threshold, Integer window, Integer count,
                                 BigDecimal ratio, String rating) {
        database.inTransaction(c -> MonitoringScenarios.declare(c, new MonitoringScenarios.Draft(
            ENTITY, code, code, method, threshold, window, count, ratio, rating,
            J.minusMonths(3), null, ACTOR, APPROVER)));
    }
}
