package io.corebanking.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.domain.account.Account;
import io.corebanking.party.RiskRating;
import java.math.BigDecimal;
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
        service().run(ENTITY, J.minusDays(3), run(), ACTOR);
        assertThat(alertes("SEUIL-ESPECES", client)).as("4 millions ne franchissent pas 5").isEmpty();

        especes(compte, "2000000", J, "cash-3");
        dater(J);
        MonitoringService.Result passe = service().run(ENTITY, J, run(), ACTOR);
        assertThat(passe.scenarios()).as("au moins le scenario du test").isGreaterThanOrEqualTo(1);
        assertThat(passe.anomalies()).isEmpty();

        List<AmlAlerts.Alert> alertes = alertes("SEUIL-ESPECES", client);
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
        service().run(ENTITY, J.plusDays(1), run(), ACTOR);
        assertThat(alertes("SEUIL-ESPECES", client))
            .as("une alerte deja levee sur la fenetre suffit").hasSize(1);
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
        service().run(ENTITY, J.minusDays(1), run(), ACTOR);
        assertThat(alertes("FRACTIONNEMENT", client))
            .as("2 700 000 depassent deja 1 000 000 des la troisieme").hasSize(1);

        AmlAlerts.Alert alerte = alertes("FRACTIONNEMENT", client).getFirst();
        assertThat(alerte.detail()).contains("toutes inferieures a 1000000");
        assertThat(alerte.amount()).isEqualTo(xof("2700000"));

        // Une operation unique au-dessus du seuil n'est pas du fractionnement : le scenario ne
        // la compte pas.
        UUID autre = client("LCB-FRANC", RiskRating.MEDIUM);
        Account direct = compte("CLI-LCB-FRANC", autre);
        especes(direct, "5000000", J, "frac-direct");
        dater(J);
        service().run(ENTITY, J, run(), ACTOR);
        assertThat(alertes("FRACTIONNEMENT", autre))
            .as("une seule grosse operation n'est pas un fractionnement").isEmpty();
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
        service().run(ENTITY, J, run(), ACTOR);
        assertThat(alertes("ATYPIE", sansProfil))
            .as("un client sans profil declare n'est pas compare").isEmpty();
        assertThat(alertes("ATYPIE", declare)).hasSize(1);

        AmlAlerts.Alert alerte = alertes("ATYPIE", declare).getFirst();
        assertThat(alerte.partyId()).isEqualTo(declare);
        assertThat(alerte.detail()).contains("profil declare");
    }

    @Test
    @DisplayName("l'atypie se lit dans les deux sens : un compte de passage sort ce qu'il vient de recevoir")
    void atypical_activity_reads_both_directions() {
        UUID passage = client("LCB-PASSAGE", RiskRating.MEDIUM);
        Account compte = compte("CLI-LCB-PASSAGE", passage);
        database.inTransaction(c -> {
            // Le client annonce beaucoup au credit, presque rien au debit : c'est la sortie qui
            // sera hors de proportion, pas l'entree.
            ActivityProfiles.declare(c, passage, xof("20000000"), xof("500000"), J.minusMonths(1),
                                     ACTOR);
            return null;
        });
        declarer("ATYPIE-DEUX-SENS", MonitoringScenarios.Method.ATYPICAL_ACTIVITY, null, 30, null,
                 new BigDecimal("3"), null);

        // L'entree reste dans le profil annonce ; la sortie, elle, fait vingt fois le debit
        // declare — c'est le compte de passage, et ne regarder que le credit ne le verrait pas.
        virementRecu(compte, "10000000", J.minusDays(1), "passage-in");
        retrait(compte, "9500000", J, "passage-out");
        dater(J);
        MonitoringService.Result resultat = service().run(ENTITY, J, run(), ACTOR);

        AmlAlerts.Alert alerte = alertes("ATYPIE-DEUX-SENS", passage).getFirst();
        assertThat(alerte.partyId()).isEqualTo(passage);
        assertThat(resultat.anomalies()).isEmpty();
    }

    @Test
    @DisplayName("un profil declare dans une autre devise que la tenue de compte est refuse : la comparaison n'aurait pas d'unite")
    void a_profile_in_another_currency_is_refused() {
        UUID id = client("LCB-DEVISE", RiskRating.MEDIUM);
        assertThatThrownBy(() -> database.inTransaction(c -> {
                ActivityProfiles.declare(c, id,
                    io.corebanking.kernel.money.Money.of("1000",
                        io.corebanking.kernel.money.Currencies.EUR),
                    io.corebanking.kernel.money.Money.of("800",
                        io.corebanking.kernel.money.Currencies.EUR),
                    J, ACTOR);
                return null;
            }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("devise de tenue de compte");
    }

    @Test
    @DisplayName("le reveil d'un compte dormant est lu contre le montant du jour")
    void a_dormant_account_waking_up_is_measured_against_the_day() {
        UUID id = client("LCB-REVEIL", RiskRating.MEDIUM);
        Account compte = compte("CLI-LCB-REVEIL", id);
        declarer("REVEIL", MonitoringScenarios.Method.DORMANT_REACTIVATION,
                 new BigDecimal("1000000"), null, null, null, null);

        // Un mouvement sur un compte qui n'a pas ete reveille aujourd'hui ne dit rien.
        especes(compte, "2000000", J.minusDays(1), "reveil-avant");
        dater(J.minusDays(1));
        service().run(ENTITY, J.minusDays(1), run(), ACTOR);
        List<AmlAlerts.Alert> avant = alertes("REVEIL", id);
        assertThat(avant).as("sans reveil constate, le scenario ne voit rien").isEmpty();

        // Le cycle de vie du compte constate le reveil ; la surveillance le confronte au montant.
        reveiller(compte, J);
        especes(compte, "3000000", J, "reveil-jour");
        dater(J);
        service().run(ENTITY, J, run(), ACTOR);
        List<AmlAlerts.Alert> apres = alertes("REVEIL", id);
        assertThat(apres).hasSize(1);
        assertThat(apres.getFirst().partyId()).isEqualTo(id);
        assertThat(apres.getFirst().amount()).isEqualTo(xof("3000000"));
        assertThat(apres.getFirst().detail()).contains("dormant");
    }

    @Test
    @DisplayName("un scenario en echec est une anomalie nommee, et les autres tournent quand meme")
    void a_failing_scenario_does_not_stop_the_others() {
        UUID id = client("LCB-PANNE", RiskRating.MEDIUM);
        Account compte = compte("CLI-LCB-PANNE", id);
        declarer("PANNE", MonitoringScenarios.Method.CASH_THRESHOLD, new BigDecimal("500000"), 30,
                 null, null, null);
        declarer("SAIN", MonitoringScenarios.Method.CASH_THRESHOLD, new BigDecimal("400000"), 30,
                 null, null, null);
        especes(compte, "600000", J, "panne-1");
        dater(J);

        // Une donnee que le scenario « PANNE » ne peut pas lire : sa fenetre est mise a zero en
        // base, ce que le service refuse a la declaration mais qu'une reprise de donnees peut
        // produire. La passe doit le nommer, pas s'arreter.
        casser("PANNE");
        MonitoringService.Result resultat = service().run(ENTITY, J, run(), ACTOR);

        assertThat(resultat.anomalies()).as("le scenario defaillant est nomme")
            .anyMatch(a -> a.contains("PANNE"));
        assertThat(alertes("SAIN", id)).as("le scenario sain a tourne quand meme").hasSize(1);
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

        assertThat(alertes("ESPECES-RENFORCE", eleve)).hasSize(1);
        assertThat(alertes("ESPECES-RENFORCE", faible))
            .as("le seuil des dossiers renforces ne vise pas les autres").isEmpty();
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

    /**
     * Les alertes d'un scenario, pour un tiers donne.
     *
     * <p>Les tests partagent une base : un scenario declare ici voit aussi les clients des autres
     * tests, ce qui est fidele a la realite — la surveillance regarde l'entite entiere. Les
     * assertions se font donc sur le tiers du test, jamais sur un compteur global.
     */
    private static List<AmlAlerts.Alert> alertes(String scenario, UUID partyId) {
        return database.inTransaction(c -> AmlAlerts.alerts(c, ENTITY, null)).stream()
            .filter(a -> scenario.equals(a.scenarioCode()) && a.partyId().equals(partyId))
            .toList();
    }

    private static void declarer(String code, MonitoringScenarios.Method method,
                                 BigDecimal threshold, Integer window, Integer count,
                                 BigDecimal ratio, String rating) {
        database.inTransaction(c -> MonitoringScenarios.declare(c, new MonitoringScenarios.Draft(
            ENTITY, code, code, method, threshold, window, count, ratio, rating,
            J.minusMonths(3), null, ACTOR, APPROVER)));
    }
}
