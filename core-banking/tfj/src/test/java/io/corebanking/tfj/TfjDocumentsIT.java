package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.party.DocumentKind;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.KycLevel;
import io.corebanking.party.KycPolicies;
import io.corebanking.party.PartyDocuments;
import io.corebanking.party.PartyFile;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'arrete constate les pieces expirees : une fois, sans bloquer la journee, et l'annulation le defait. */
class TfjDocumentsIT extends TfjTestBase {

    @Test
    @DisplayName("l'arrete constate la piece expiree, ne la constate qu'une fois, ne bloque pas la journee, et son annulation efface le constat")
    void the_day_end_notices_expired_documents_once_without_blocking() {
        LocalDate jour = businessDate();                                 // lundi 14 septembre 2026
        PartyService parties = new PartyService(database, Screening.NONE);
        UUID client = parties.create(new PartyService.Draft(
            ENTITY, "DOC-001", PartyKind.NATURAL_PERSON, "Client pieces", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-DOC-001")), ACTOR));
        parties.verifyKyc(client, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);

        // Une piece d'identite perimee hier, une attestation de revenu toujours valable.
        database.inTransaction(c -> PartyDocuments.deposit(c, new PartyDocuments.Deposit(
            ENTITY, client, DocumentKind.IDENTITY, "CNI-DOC-001", "ONECI",
            jour.minusYears(5), jour.minusDays(1), jour.minusYears(5), ACTOR)));
        database.inTransaction(c -> PartyDocuments.deposit(c, new PartyDocuments.Deposit(
            ENTITY, client, DocumentKind.INCOME_PROOF, "BULLETIN-2026", "Employeur",
            jour.minusMonths(2), jour.plusYears(1), jour.minusMonths(2), ACTOR)));

        TfjRun lundi = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(lundi.isCompleted()).as(lundi.summary()).isTrue();
        TfjRun.StepExecution constat = etape(lundi, "DOCUMENT_EXPIRY");
        assertThat(constat.written()).isEqualTo(1);
        assertThat(constat.anomalies()).singleElement().asString()
            .contains("DOC-001").contains("IDENTITY");

        // Une politique exige la piece d'identite : le dossier devient incomplet, et plus rien ne
        // s'y ouvre. Les comptes existants, eux, continuent de fonctionner.
        database.inTransaction(c -> KycPolicies.declare(c, new KycPolicies.Draft(
            ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD,
            java.util.Set.of(DocumentKind.IDENTITY), false, null, ACTOR, APPROVER)));
        PartyFile.Completeness dossier = database.inTransaction(
            c -> PartyFile.completeness(c, client, jour));
        assertThat(dossier.complete()).isFalse();
        assertThat(dossier.expired()).containsExactly(DocumentKind.IDENTITY);
        database.inTransaction(c -> {
            assertThatThrownBy(() -> PartyService.requireOnboardable(c, client))
                .isInstanceOf(PartyService.PartyNotOperableException.class)
                .hasMessageContaining("dossier incomplet");
            assertThat(PartyService.requireOperable(c, client).reference()).isEqualTo("DOC-001");
            return null;
        });

        // Mardi : la piece est deja constatee, on ne la reconstate pas.
        TfjRun mardi = engine.run(ENTITY, jour.plusDays(1), ACTOR, RunMode.REAL);
        assertThat(mardi.isCompleted()).as(mardi.summary()).isTrue();
        assertThat(etape(mardi, "DOCUMENT_EXPIRY").written()).isZero();

        // L'annulation de l'arrete efface son constat : la journee peut etre rejouee a l'identique.
        engine.cancel(mardi.id(), ACTOR, jour.plusDays(1), "reprise");
        engine.cancel(lundi.id(), ACTOR, jour, "reprise");
        List<PartyDocuments.Document> pieces = database.inTransaction(
            c -> PartyDocuments.of(c, client, false));
        assertThat(pieces).hasSize(2);
        TfjRun rejeu = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(rejeu.isCompleted()).as(rejeu.summary()).isTrue();
        assertThat(etape(rejeu, "DOCUMENT_EXPIRY").written()).isEqualTo(1);

        // Piece renouvelee : le dossier redevient complet et le client peut a nouveau s'etendre.
        database.inTransaction(c -> PartyDocuments.deposit(c, new PartyDocuments.Deposit(
            ENTITY, client, DocumentKind.IDENTITY, "CNI-DOC-001-BIS", "ONECI",
            jour, jour.plusYears(10), jour, ACTOR)));
        PartyFile.Completeness renouvele = database.inTransaction(
            c -> PartyFile.completeness(c, client, jour));
        assertThat(renouvele.complete()).as(renouvele.summary()).isTrue();
        database.inTransaction(c -> PartyService.requireOnboardable(c, client));
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
