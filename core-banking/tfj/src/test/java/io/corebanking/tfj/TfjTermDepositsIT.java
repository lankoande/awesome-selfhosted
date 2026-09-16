package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.deposits.TermDepositCatalog;
import io.corebanking.deposits.TermDepositService;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.IdentifierKind;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'arrete constate les interets des depots a terme jour apres jour, denoue les termes arrives, et
 * defait l'un comme l'autre quand il est annule.
 */
class TfjTermDepositsIT extends TfjTestBase {

    @Test
    @DisplayName("l'arrete constate les interets du depot a terme chaque nuit, denoue le terme, et son annulation contre-passe les deux")
    void the_day_end_accrues_and_matures_term_deposits() {
        LocalDate jour = businessDate();                                 // 14 septembre 2026
        Account caisse = account("CAISSE-DAT", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-DAT", AccountKind.GL, NormalBalance.CREDIT);
        Account charges = account("CHARGES-DAT", AccountKind.GL, NormalBalance.DEBIT);
        Account irc = account("IRC-DAT", AccountKind.GL, NormalBalance.CREDIT);
        Account chargesCc = account("CHARGES-CC-DAT", AccountKind.GL, NormalBalance.DEBIT);
        Account courusCc = account("COURUS-CC-DAT", AccountKind.GL, NormalBalance.CREDIT);
        produitCourant("CC-DAT", chargesCc, courusCc);
        produitTerme("DAT-COURT", courus, charges, irc);

        PartyService parties = new PartyService(database, Screening.NONE);
        UUID titulaire = parties.create(new PartyService.Draft(
            ENTITY, "T-DAT", PartyKind.NATURAL_PERSON, "Client DAT", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-DAT")), ACTOR));
        parties.verifyKyc(titulaire, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);
        Account courant = compte("CLI-DAT-CC", "CC-DAT", jour, titulaire);
        Account depot = compte("CLI-DAT-TERME", "DAT-COURT", jour, titulaire);
        deposit(courant, caisse, "4000000", jour, "dat-0");

        // Un depot a terme de trois mois, souscrit le jour meme.
        TermDepositService service = new TermDepositService(database, postingService);
        TermDepositService.TermDeposit dat = service.subscribe(new TermDepositService.Draft(
            ENTITY, "DAT-TFJ-1", depot.id(), courant.id(), xof("3000000"), null, 3, null,
            TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));
        assertThat(dat.maturityDate()).isEqualTo(LocalDate.of(2026, 12, 14));

        // La premiere nuit constate une journee d'interets : 3 000 000 x 4 % x 1 / 365 = 329.
        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(etape(run, "TERM_DEPOSIT_ACCRUAL").written()).isEqualTo(1);
        assertThat(etape(run, "TERM_DEPOSIT_MATURITY").read()).isZero();
        assertThat(solde(courus)).isEqualTo(xof("329"));
        assertThat(solde(charges)).isEqualTo(xof("329"));

        // Annule, l'arrete defait ce qu'il a constate : le sous-livre revient a zero, et la
        // journee rejouee le reconstitue a l'identique.
        engine.cancel(run.id(), ACTOR, jour.plusDays(1), "erreur de parametrage");
        assertThat(solde(courus).isZero()).isTrue();
        TermDepositService.TermDeposit defait = lire(dat.id());
        assertThat(defait.accruedTotal().isZero()).as("le sous-livre suit la contre-passation")
            .isTrue();
        assertThat(defait.accruedThrough()).isNull();

        TfjRun rejeu = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(rejeu.isCompleted()).as(rejeu.summary()).isTrue();
        assertThat(solde(courus)).isEqualTo(xof("329"));
        assertThat(lire(dat.id()).accruedThrough()).isEqualTo(jour);
    }

    // ------------------------------------------------------------------ outillage

    private static void produitCourant(String code, Account charges, Account courus) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "0");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());
        publier(code, "CURRENT_ACCOUNT", parametres);
    }

    private static void produitTerme(String code, Account courus, Account charges, Account irc) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(TermDepositCatalog.P_RATE, "4");
        parametres.put(TermDepositCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(TermDepositCatalog.P_MIN_AMOUNT, "1000000");
        parametres.put(TermDepositCatalog.P_MIN_MONTHS, "3");
        parametres.put(TermDepositCatalog.P_MAX_MONTHS, "60");
        parametres.put(TermDepositCatalog.P_ACCRUED_INTEREST, courus.id().toString());
        parametres.put(TermDepositCatalog.P_INTEREST_EXPENSE, charges.id().toString());
        parametres.put(TermDepositCatalog.P_WITHHOLDING_RATE, "10");
        parametres.put(TermDepositCatalog.P_WITHHOLDING_ACCOUNT, irc.id().toString());
        publier(code, "TERM_DEPOSIT", parametres);
    }

    private static void publier(String code, String famille, Map<String, String> parametres) {
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, famille, code, "XOF", J1.minusYears(2), null,
                new LinkedHashMap<>(parametres), List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    private static Account compte(String code, String produit, LocalDate jour, UUID titulaire) {
        Account account = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, account.id(), produit, jour.minusMonths(1), null);
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER,
                                  jour.minusMonths(1), ACTOR);
            return null;
        });
        return account;
    }

    private static TermDepositService.TermDeposit lire(UUID id) {
        return database.inTransaction(c -> TermDepositService.require(c, id));
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
