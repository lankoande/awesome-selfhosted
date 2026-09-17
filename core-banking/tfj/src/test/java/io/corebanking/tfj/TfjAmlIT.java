package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.compliance.AmlAlerts;
import io.corebanking.compliance.MonitoringScenarios;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La surveillance LCB-FT dans l'arrete : elle regarde la journee arretee, ne l'arrete pas, et
 * disparait avec elle si l'arrete est annule.
 */
class TfjAmlIT extends TfjTestBase {

    @Test
    @DisplayName("l'arrete leve les alertes de la journee sans bloquer, et son annulation les efface")
    void the_day_end_raises_alerts_without_blocking_and_cancelling_erases_them() {
        LocalDate jour = businessDate();
        Account caisse = account("CAISSE-AML", AccountKind.GL, NormalBalance.DEBIT);

        PartyService parties = new PartyService(database, Screening.NONE);
        UUID titulaire = parties.create(new PartyService.Draft(
            ENTITY, "T-AML", PartyKind.NATURAL_PERSON, "Client AML", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-AML")), ACTOR));
        parties.verifyKyc(titulaire, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);
        Account compte = account("CLI-AML", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            AccountHolders.attach(c, compte.id(), titulaire, HolderRole.HOLDER,
                                  jour.minusMonths(1), ACTOR);
            return null;
        });
        database.inTransaction(c -> MonitoringScenarios.declare(c, new MonitoringScenarios.Draft(
            ENTITY, "TFJ-ESPECES", "Especes cumulees", MonitoringScenarios.Method.CASH_THRESHOLD,
            new BigDecimal("5000000"), 30, null, null, null, jour.minusMonths(1), null,
            ACTOR, APPROVER)));

        // Un versement d'especes au guichet, au-dela du seuil, dans la journee arretee. Le type
        // d'operation compte : la surveillance des especes ne lit pas les virements.
        especes(compte, caisse, "6000000", jour, "aml-1");

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        TfjRun.StepExecution etape = etape(run, "AML_MONITORING");
        assertThat(etape.read()).as("un scenario actif examine").isEqualTo(1);
        assertThat(etape.written()).as("une alerte levee").isEqualTo(1);

        List<AmlAlerts.Alert> alertes = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, "OPEN"));
        assertThat(alertes).hasSize(1);
        assertThat(alertes.getFirst().partyId()).isEqualTo(titulaire);
        assertThat(alertes.getFirst().amount()).isEqualTo(Money.of("6000000", Currencies.XOF));
        assertThat(alertes.getFirst().items()).hasSize(1);

        // Annule, l'arrete emporte l'alerte : les operations qui la fondaient n'existent plus.
        engine.cancel(run.id(), ACTOR, jour.plusDays(1), "erreur de saisie du versement");
        List<AmlAlerts.Alert> apres = database.inTransaction(
            c -> AmlAlerts.alerts(c, ENTITY, null));
        assertThat(apres).as("une alerte sans fait derriere elle ne reste pas").isEmpty();
    }

    @Test
    @DisplayName("un scenario mal parametre ne bloque pas l'arrete : il le signale en anomalie")
    void a_broken_scenario_does_not_stop_the_day() {
        LocalDate jour = businessDate();
        // Un scenario dont le compte de population ne correspond a personne : il s'execute, ne
        // leve rien, et la journee passe. La conformite n'arrete jamais la comptabilite.
        database.inTransaction(c -> MonitoringScenarios.declare(c, new MonitoringScenarios.Draft(
            ENTITY, "TFJ-VIDE-" + jour, "Population vide",
            MonitoringScenarios.Method.CASH_THRESHOLD, new BigDecimal("1"), 30, null, null,
            "HIGH", jour.minusMonths(1), jour, ACTOR, APPROVER)));

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(etape(run, "AML_MONITORING").blocking())
            .as("la conformite constate, elle n'arrete pas la banque").isFalse();
    }

    /** Un versement d'especes au guichet : c'est son type qui le rend visible au scenario. */
    private static void especes(Account client, Account caisse, String montant, LocalDate jour,
                                String key) {
        Money amount = Money.of(montant, Currencies.XOF);
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, jour, "CASH_DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), amount, jour, null),
                    PostingLine.credit(client.id(), amount, jour, null))));
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
