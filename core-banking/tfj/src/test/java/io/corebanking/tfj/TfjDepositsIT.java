package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.deposits.DepositCatalog;
import io.corebanking.deposits.Holds;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.party.IdentifierKind;
import io.corebanking.party.KycStatus;
import io.corebanking.party.PartyIdentifier;
import io.corebanking.party.PartyKind;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.party.Screening;
import io.corebanking.product.ProductCatalog;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'arrete leve les blocages echus, constate la dormance, expire les revues ; son annulation defait tout. */
class TfjDepositsIT extends TfjTestBase {

    @Test
    @DisplayName("la journee expire les blocages, passe les comptes dormants et les revues depassees ; l'annulation les restaure")
    void holds_dormancy_and_kyc_follow_the_day() {
        LocalDate jour = businessDate();                                 // 14 septembre 2026
        Account caisse = account("CAISSE-DEP", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-DEP", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-DEP", AccountKind.GL, NormalBalance.CREDIT);
        produit("EP-DEP", Map.of(
            ProductCatalog.P_RATE, "6",
            ProductCatalog.P_DAY_COUNT, "ACT_365",
            ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name(),
            ProductCatalog.P_CAPITALISATION, "QUARTERLY",
            ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString(),
            ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString(),
            DepositCatalog.P_DORMANCY_MONTHS, "12"));

        // Un tiers a haut risque verifie il y a treize mois : sa revue annuelle est depassee.
        PartyService parties = new PartyService(database, Screening.NONE);
        UUID tiers = parties.create(new PartyService.Draft(
            ENTITY, "T-DEP", PartyKind.NATURAL_PERSON, "Client DEP", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-DEP")), ACTOR));
        parties.verifyKyc(tiers, RiskRating.HIGH, jour.minusMonths(13), ACTOR, APPROVER);

        // Un compte vivant, avec un blocage de montant qui expire ce jour.
        Account actif = account("CLI-DEP-ACTIF", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, actif.id(), "EP-DEP", jour, null);
            return null;
        });
        deposit(actif, caisse, "500000", jour, "dep-actif");
        UUID blocage = database.inTransaction(c -> Holds.place(c, new Holds.Placement(
            actif.id(), Money.of("100000", Currencies.XOF), "CAUTION", null, jour, jour, ACTOR)));

        // Un compte ouvert il y a quatorze mois, sans aucune operation de son client.
        Account oublie = new Account(UUID.randomUUID(), ENTITY, "CLI-DEP-OUBLIE",
                                     AccountKind.CUSTOMER, NormalBalance.CREDIT, Currencies.XOF,
                                     true, true, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, oublie, jour.minusMonths(14));
            ProductCatalog.assignProduct(c, oublie.id(), "EP-DEP", jour.minusMonths(14), null);
            return null;
        });

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(run.steps()).allMatch(
            step -> step.status() == TfjRun.StepExecution.Status.COMPLETED);
        assertThat(etape(run, "HOLD_EXPIRY").written()).isEqualTo(1);
        assertThat(etape(run, "DORMANCY").written()).isEqualTo(1);
        assertThat(etape(run, "KYC_REVIEW").written()).isEqualTo(1);
        assertThat(etape(run, "KYC_REVIEW").anomalies()).singleElement().asString()
            .contains("T-DEP");
        database.inTransaction(c -> {
            assertThat(Holds.activeOn(c, actif.id())).isEmpty();
            assertThat(statut(c, oublie.id())).isEqualTo(AccountStatus.DORMANT);
            assertThat(statut(c, actif.id())).isEqualTo(AccountStatus.ACTIVE);
            return null;
        });
        assertThat(parties.require(tiers).kycStatus()).isEqualTo(KycStatus.EXPIRED);

        // L'annulation repose le blocage, reveille le compte et restaure la revue.
        engine.cancel(run.id(), ACTOR, jour.plusDays(1), "erreur de parametrage");

        database.inTransaction(c -> {
            assertThat(Holds.activeOn(c, actif.id())).extracting(Holds.Hold::id)
                .containsExactly(blocage);
            assertThat(statut(c, oublie.id())).isEqualTo(AccountStatus.ACTIVE);
            return null;
        });
        assertThat(parties.require(tiers).kycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(businessDate()).isEqualTo(jour);
    }

    // ------------------------------------------------------------------ outillage

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }

    private static AccountStatus statut(java.sql.Connection c, UUID accountId) {
        return Accounts.loadAll(c, Set.of(accountId)).get(accountId).status();
    }

    private static void produit(String code, Map<String, String> parametres) {
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, "SAVINGS_ACCOUNT", code, "XOF", J1.minusYears(2), null,
                new LinkedHashMap<>(parametres), List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }
}
