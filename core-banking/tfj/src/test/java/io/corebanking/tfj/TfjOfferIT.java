package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.loan.service.LendingPolicies;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.loan.service.LoanOrigination;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import io.corebanking.product.ProductCatalog;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'offre de credit a une fin : l'arrete la constate, et son annulation la rend a l'accord. */
class TfjOfferIT extends TfjTestBase {

    @Test
    @DisplayName("l'arrete eteint l'offre echue sans bloquer la journee, et l'annulation de l'arrete la rend a l'accord")
    void the_day_end_expires_offers_and_the_cancellation_gives_them_back() {
        LocalDate jour = businessDate();                                 // lundi 14 septembre 2026
        Account pret = account("OFFRE-PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT);
        Account courant = account("OFFRE-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Account creances = account("OFFRE-CREANCES", AccountKind.GL, NormalBalance.DEBIT);
        Account produits = account("OFFRE-PRODUITS", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("OFFRE-TAXE", AccountKind.GL, NormalBalance.CREDIT);
        Account courus = account("OFFRE-ICNE", AccountKind.GL, NormalBalance.DEBIT);
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, creances.id().toString());
        parametres.put(LoanCatalog.P_ACCRUED_INTEREST, courus.id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "CRED-OFFRE", "TERM_LOAN", "Credit amortissable", "XOF",
                jour.minusMonths(1), null, parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });

        PartyService parties = new PartyService(database, Screening.NONE);
        UUID client = parties.create(new PartyService.Draft(
            ENTITY, "T-OFFRE", PartyKind.NATURAL_PERSON, "Client offre", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-OFFRE")), ACTOR));
        parties.verifyKyc(client, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);

        // Une offre valable deux jours : l'accord est pris aujourd'hui.
        database.inTransaction(c -> LendingPolicies.declare(c, new LendingPolicies.Draft(
            ENTITY, "CRED-OFFRE", null, null, null, null, false, 2, jour.minusMonths(1), null,
            ACTOR, APPROVER)));
        UUID demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                ENTITY, null, "DEM-TFJ-1", client, "CRED-OFFRE",
                io.corebanking.kernel.money.Money.of("1000000", Currencies.XOF), 12, "outillage",
                jour, ACTOR))).id();
        database.inTransaction(c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
            demande, io.corebanking.kernel.money.Money.of("2000000", Currencies.XOF), null, null,
            new BigDecimal("12"), null, null, jour, ACTOR)));
        database.inTransaction(c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
            demande, LoanOrigination.Outcome.APPROVED,
            io.corebanking.kernel.money.Money.of("1000000", Currencies.XOF), 12,
            new BigDecimal("12"), jour, "accord", null, ACTOR, APPROVER)));

        // Lundi, mardi, mercredi : l'offre vaut jusqu'au 16 inclus.
        TfjRun lundi = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(lundi.isCompleted()).as(lundi.summary()).isTrue();
        assertThat(etape(lundi, "OFFER_EXPIRY").written()).isZero();
        TfjRun mardi = engine.run(ENTITY, jour.plusDays(1), ACTOR, RunMode.REAL);
        assertThat(etape(mardi, "OFFER_EXPIRY").written()).isZero();
        TfjRun mercredi = engine.run(ENTITY, jour.plusDays(2), ACTOR, RunMode.REAL);
        assertThat(etape(mercredi, "OFFER_EXPIRY").written()).isZero();

        // Jeudi : l'offre est echue. La journee passe — aucun engagement n'etait porte.
        TfjRun jeudi = engine.run(ENTITY, jour.plusDays(3), ACTOR, RunMode.REAL);
        assertThat(jeudi.isCompleted()).as(jeudi.summary()).isTrue();
        assertThat(etape(jeudi, "OFFER_EXPIRY").written()).isEqualTo(1);
        assertThat(etape(jeudi, "OFFER_EXPIRY").anomalies()).singleElement().asString()
            .contains("DEM-TFJ-1");
        assertThat(database.inTransaction(c -> LoanOrigination.require(c, demande)).status())
            .isEqualTo(LoanOrigination.Status.EXPIRED);

        // L'annulation de l'arrete rend l'offre a l'accord : la journee se rejoue a l'identique.
        engine.cancel(jeudi.id(), ACTOR, jour.plusDays(3), "reprise");
        assertThat(database.inTransaction(c -> LoanOrigination.require(c, demande)).status())
            .isEqualTo(LoanOrigination.Status.APPROVED);
        TfjRun rejeu = engine.run(ENTITY, jour.plusDays(3), ACTOR, RunMode.REAL);
        assertThat(rejeu.isCompleted()).as(rejeu.summary()).isTrue();
        assertThat(etape(rejeu, "OFFER_EXPIRY").written()).isEqualTo(1);
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
