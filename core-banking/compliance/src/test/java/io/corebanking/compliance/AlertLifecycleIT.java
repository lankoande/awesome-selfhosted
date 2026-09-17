package io.corebanking.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.PartyStatus;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La vie d'une alerte : de la correspondance au filtrage jusqu'a la declaration de soupcon.
 *
 * <p>Ce que ces tests tiennent : une correspondance laisse une file de travail et pas seulement un
 * dossier bloque ; un classement se motive ; une declaration se decide a deux et scelle les
 * alertes qu'elle cite ; une journee annulee efface ce qu'elle a leve, sauf ce qu'on a deja
 * instruit.
 */
class AlertLifecycleIT extends ComplianceTestBase {

    @Test
    @DisplayName("une correspondance au filtrage bloque le dossier et laisse une alerte a instruire")
    void screening_leaves_an_alert_behind() {
        PartyService filtre = new PartyService(database, new AlertingScreening(
            subject -> subject.displayName().contains("Sanctionne")
                ? Optional.of(new Screening.Match("UE", "EU-2024-17", "homonymie forte"))
                : Optional.empty(),
            database));

        UUID id = filtre.create(new PartyService.Draft(ENTITY, "LCB-FILTRE", PartyKind.LEGAL_PERSON,
            "Societe Sanctionnee SA", null, "CI", "PME",
            List.of(PartyIdentifier.of(IdentifierKind.TRADE_REGISTRY, "CI-ABJ-2001-B-9999")),
            ACTOR));

        assertThat(filtre.require(id).status()).isEqualTo(PartyStatus.BLOCKED);
        AmlAlerts.Alert alerte = alerteDe(id);
        assertThat(alerte.origin()).isEqualTo(AmlAlerts.Origin.SCREENING);
        assertThat(alerte.scenarioCode()).as("une correspondance ne vient pas d'un compteur")
            .isNull();
        assertThat(alerte.detail()).contains("EU-2024-17");
        assertThat(alerte.amount()).isNull();
        assertThat(alerte.status()).isEqualTo("OPEN");

        // Le tiers non filtre se cree sans alerte : le filtrage ne salit pas la file.
        UUID ordinaire = filtre.create(new PartyService.Draft(ENTITY, "LCB-PROPRE",
            PartyKind.NATURAL_PERSON, "Client ordinaire", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-LCB-PROPRE")), ACTOR));
        List<AmlAlerts.Alert> toutes = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null));
        assertThat(toutes).noneMatch(a -> a.partyId().equals(ordinaire));
    }

    @Test
    @DisplayName("l'instruction nomme un responsable, le classement porte son motif, et une alerte classee ne se reprend pas")
    void an_alert_is_assigned_then_closed_with_a_reason() {
        UUID client = client("LCB-VIE", RiskRating.MEDIUM);
        UUID alertId = lever(client, "levee pour instruction");

        AmlAlerts.Alert prise = database.inTransaction(c -> AmlAlerts.assign(c, alertId, ACTOR));
        assertThat(prise.status()).isEqualTo("UNDER_REVIEW");
        assertThat(prise.assignedTo()).isEqualTo(ACTOR);

        // Classer sans motif : refuse. C'est le motif que l'inspection vient lire.
        assertThatThrownBy(() -> database.inTransaction(
                c -> AmlAlerts.close(c, alertId, J, "  ", ACTOR)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("motif");

        AmlAlerts.Alert classee = database.inTransaction(
            c -> AmlAlerts.close(c, alertId, J, "salaire du mois, piece au dossier", APPROVER));
        assertThat(classee.status()).isEqualTo("CLOSED");
        assertThat(classee.closureReason()).contains("salaire");
        assertThat(classee.closedBy()).isEqualTo(APPROVER);
        assertThat(classee.open()).isFalse();

        assertThatThrownBy(() -> database.inTransaction(
                c -> AmlAlerts.close(c, alertId, J, "encore", ACTOR)))
            .isInstanceOf(AmlAlerts.AlertStateException.class).hasMessageContaining("deja CLOSED");
        assertThatThrownBy(() -> database.inTransaction(c -> AmlAlerts.assign(c, alertId, ACTOR)))
            .isInstanceOf(AmlAlerts.AlertStateException.class);
    }

    @Test
    @DisplayName("la declaration se decide a deux, scelle les alertes qu'elle cite, et ne les declare pas deux fois")
    void a_report_seals_the_alerts_it_cites() {
        UUID client = client("LCB-DECLA", RiskRating.MEDIUM);
        UUID premiere = lever(client, "especes repetees");
        UUID seconde = lever(client, "fractionnement");

        assertThatThrownBy(() -> new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-001",
                J, "expose", List.of(premiere), ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThatThrownBy(() -> new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-001",
                J, "expose", List.of(), ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cite les alertes");
        assertThatThrownBy(() -> new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-001",
                J, "  ", List.of(premiere), ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expose des faits");

        UUID report = database.inTransaction(c -> SuspiciousActivityReports.draft(c,
            new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-001", J,
                "Depots d'especes sans rapport avec l'activite declaree.",
                List.of(premiere, seconde), ACTOR, APPROVER)));

        List<UUID> scellees = database.inTransaction(c -> {
            assertThat(AmlAlerts.require(c, premiere).status()).isEqualTo("REPORTED");
            assertThat(AmlAlerts.require(c, premiere).reportId()).isEqualTo(report);
            assertThat(AmlAlerts.require(c, seconde).status()).isEqualTo("REPORTED");
            return SuspiciousActivityReports.require(c, report).alertIds();
        });
        assertThat(scellees).containsExactlyInAnyOrder(premiere, seconde);

        // Une alerte declaree ne se redeclare pas : deux dossiers pour un seul fait.
        assertThatThrownBy(() -> database.inTransaction(c -> SuspiciousActivityReports.draft(c,
                new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-002", J, "bis",
                    List.of(premiere), ACTOR, APPROVER))))
            .isInstanceOf(SuspiciousActivityReports.ReportRefusedException.class)
            .hasMessageContaining("deja couverte");

        // Une alerte d'un autre tiers ne se glisse pas dans la declaration.
        UUID autre = client("LCB-AUTRE", RiskRating.MEDIUM);
        UUID ailleurs = lever(autre, "sans rapport");
        assertThatThrownBy(() -> database.inTransaction(c -> SuspiciousActivityReports.draft(c,
                new SuspiciousActivityReports.Draft(ENTITY, client, "DS-2026-003", J, "melange",
                    List.of(ailleurs), ACTOR, APPROVER))))
            .isInstanceOf(SuspiciousActivityReports.ReportRefusedException.class)
            .hasMessageContaining("ne porte pas sur le tiers declare");

        // La transmission porte la reference rendue par la cellule ; elle ne se rejoue pas.
        SuspiciousActivityReports.Report transmise = database.inTransaction(
            c -> SuspiciousActivityReports.transmit(c, report, J.plusDays(1), "CENTIF-2026-4417"));
        assertThat(transmise.transmitted()).isTrue();
        assertThat(transmise.transmissionReference()).isEqualTo("CENTIF-2026-4417");
        assertThatThrownBy(() -> database.inTransaction(
                c -> SuspiciousActivityReports.transmit(c, report, J.plusDays(2), "CENTIF-BIS")))
            .isInstanceOf(SuspiciousActivityReports.ReportRefusedException.class)
            .hasMessageContaining("deja ete transmise");
    }

    @Test
    @DisplayName("l'annulation d'un arrete efface les alertes qu'il a levees, mais pas celles qu'on a deja prises en instruction")
    void cancelling_a_run_erases_only_what_nobody_has_touched() {
        UUID client = client("LCB-ANNUL", RiskRating.MEDIUM);
        UUID run = UUID.randomUUID();
        UUID intacte = lever(client, "levee par l'arrete", run);
        UUID instruite = lever(client, "levee puis prise en charge", run);
        UUID autreRun = lever(client, "levee par un autre arrete", UUID.randomUUID());
        database.inTransaction(c -> AmlAlerts.assign(c, instruite, ACTOR));

        int effacees = database.inTransaction(c -> AmlAlerts.cancelRun(c, run));
        assertThat(effacees).isEqualTo(1);
        database.inTransaction(c -> {
            assertThat(AmlAlerts.find(c, intacte)).isEmpty();
            assertThat(AmlAlerts.require(c, instruite).status()).isEqualTo("UNDER_REVIEW");
            assertThat(AmlAlerts.require(c, autreRun).status()).isEqualTo("OPEN");
            return null;
        });
    }

    // ------------------------------------------------------------------ outillage

    private static UUID lever(UUID partyId, String detail) {
        return lever(partyId, detail, null);
    }

    private static UUID lever(UUID partyId, String detail, UUID runId) {
        return database.inTransaction(c -> AmlAlerts.raise(c, ENTITY, partyId, "SCENARIO-TEST",
            AmlAlerts.Origin.MONITORING, J, detail, xof("2500000"), List.of(), runId));
    }

    private static AmlAlerts.Alert alerteDe(UUID partyId) {
        return database.inTransaction(c -> AmlAlerts.alerts(c, ENTITY, null)).stream()
            .filter(a -> a.partyId().equals(partyId)).findFirst().orElseThrow();
    }
}
