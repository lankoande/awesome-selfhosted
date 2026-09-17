package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.regulatory.RegulatoryDeclarations;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'arrete constate les echeances declaratives depassees — et ne s'arrete pas pour autant : un
 * etat non transmis n'empeche pas la banque d'arreter ses comptes, puisque c'est l'arrete qui
 * produit les donnees de l'etat.
 */
class TfjRegulatoryIT extends TfjTestBase {

    @Test
    @DisplayName("une echeance declarative depassee est une anomalie de l'arrete, jamais un blocage")
    void an_overdue_declaration_is_an_anomaly_not_a_stop() {
        LocalDate jour = businessDate();
        // Une declaration mensuelle a sept jours, en vigueur depuis un an : les mois clos avant
        // la journee courante sont dus, et rien n'a ete transmis.
        database.inEntity(ENTITY, c -> RegulatoryDeclarations.declare(c,
            new RegulatoryDeclarations.Draft(ENTITY, "TFJ-SITUATION", "Situation comptable",
                RegulatoryDeclarations.Recipient.CENTRAL_BANK,
                RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                RegulatoryDeclarations.Frequency.MONTHLY, 7, null, jour.minusYears(1), null,
                ACTOR, APPROVER)));

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();

        TfjRun.StepExecution etape = etape(run, "REGULATORY_DEADLINES");
        assertThat(etape.blocking()).as("la conformite constate, elle n'arrete pas la banque")
            .isFalse();
        assertThat(etape.read()).as("une declaration active surveillee").isEqualTo(1);
        assertThat(etape.written()).as("l'etape ne comptabilise rien").isZero();
        assertThat(etape.anomalies()).isNotEmpty();
        assertThat(etape.anomalies().getFirst())
            .contains("TFJ-SITUATION").contains("echeance").contains("depassee");
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
