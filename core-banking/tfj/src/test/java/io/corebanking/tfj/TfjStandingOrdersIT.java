package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.calendar.BusinessDayConvention;
import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.OffsetUnit;
import io.corebanking.calendar.ValueDateRule;
import io.corebanking.deposits.DepositCatalog;
import io.corebanking.deposits.PaymentService;
import io.corebanking.deposits.StandingOrderService;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.Direction;
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
 * L'arrete execute les ordres permanents a leur echeance : il vire, il depose un ordre de
 * paiement vers l'exterieur, et il enregistre le rejet de celui que la provision ne couvre pas —
 * sans arreter la journee. Annule, il rend chaque ordre a l'echeance qu'il a trouvee.
 */
class TfjStandingOrdersIT extends TfjTestBase {

    @Test
    @DisplayName("a l'echeance, l'arrete vire l'ordre interne, depose un ordre de paiement pour l'externe et enregistre le rejet sans provision ; a blanc rien ne reste ; annule, chaque ordre revient a son echeance sans tentative consommee et l'ordre de paiement est solde")
    void standing_orders_follow_the_day() {
        LocalDate jour = businessDate();                                 // 14 septembre 2026
        LocalDate echeance = jour.plusDays(1);                           // mardi 15 septembre
        Account caisse = account("CAISSE-OP", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-OP", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-OP", AccountKind.GL, NormalBalance.CREDIT);
        Account frais = account("FRAIS-OP", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("TAXE-OP", AccountKind.GL, NormalBalance.CREDIT);
        Account reglement = account("REGLEMENT-OP", AccountKind.GL, NormalBalance.CREDIT);
        Account nostro = account("NOSTRO-OP", AccountKind.NOSTRO, NormalBalance.DEBIT);
        database.inTransaction(c -> {
            regle(c, "TRANSFER", Direction.DEBIT);
            regle(c, "TRANSFER", Direction.CREDIT);
            regle(c, "PAYMENT_ORDER", Direction.DEBIT);
            return null;
        });
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "0");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());
        parametres.put(DepositCatalog.P_FEE_INCOME, frais.id().toString());
        parametres.put(DepositCatalog.P_TAX_RATE, "18");
        parametres.put(DepositCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(DepositCatalog.P_TRANSFER_FEE, "500");
        parametres.put(DepositCatalog.P_PAYMENT_FEE, "1000");
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        produit("CC-OP", parametres);
        PartyService parties = new PartyService(database, Screening.NONE);
        UUID titulaire = parties.create(new PartyService.Draft(
            ENTITY, "T-OP", PartyKind.NATURAL_PERSON, "Client OP", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-OP")), ACTOR));
        parties.verifyKyc(titulaire, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);
        Account payeur = compteClient("CLI-OP-PAYEUR", jour, titulaire);
        Account beneficiaire = compteClient("CLI-OP-BENEF", jour, titulaire);
        Account pauvre = compteClient("CLI-OP-PAUVRE", jour, titulaire);
        deposit(payeur, caisse, "100000", jour, "op-0");
        deposit(pauvre, caisse, "100", jour, "op-1");

        StandingOrderService service = new StandingOrderService(database, postingService);
        // Le loyer : un virement interne de 30 000, tous les mois, trois fois.
        StandingOrderService.StandingOrder loyer = service.register(
            new StandingOrderService.Draft(ENTITY, payeur.id(), "OP-1",
                StandingOrderService.Kind.FIXED, xof("30000"), null, beneficiaire.id(), null, null,
                null, Periodicity.MONTHLY, echeance, null, 3, 2, "loyer", ACTOR, APPROVER));
        // L'epargne du frere, dans une autre banque : l'ordre ne comptabilise pas lui-meme, il
        // depose un ordre de paiement.
        StandingOrderService.StandingOrder pension = service.register(
            new StandingOrderService.Draft(ENTITY, payeur.id(), "OP-2",
                StandingOrderService.Kind.FIXED, xof("20000"), null, null, "Frere du client",
                "BK-CI-004", "CI93CI0040001111222233334444", Periodicity.MONTHLY, echeance, null,
                null, 2, "pension", ACTOR, APPROVER));
        // Celui que la provision ne couvrira pas.
        StandingOrderService.StandingOrder decouvert = service.register(
            new StandingOrderService.Draft(ENTITY, pauvre.id(), "OP-3",
                StandingOrderService.Kind.FIXED, xof("5000"), null, beneficiaire.id(), null, null,
                null, Periodicity.MONTHLY, echeance, null, null, 2, "cotisation", ACTOR,
                APPROVER));
        assertThat(loyer.dueDate()).isEqualTo(echeance);
        assertThat(loyer.occurrence()).isZero();

        // La veille ne les touche pas : l'echeance est demain.
        TfjRun veille = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(veille.isCompleted()).as(veille.summary()).isTrue();
        assertThat(etape(veille, "STANDING_ORDERS").read()).isZero();
        assertThat(businessDate()).isEqualTo(echeance);
        assertThat(solde(payeur)).isEqualTo(xof("100000"));

        // A blanc : les trois echeances sont traitees, et rien ne reste.
        TfjRun blanc = engine.run(ENTITY, echeance, ACTOR, RunMode.DRY_RUN);
        assertThat(blanc.isCompleted()).as(blanc.summary()).isTrue();
        assertThat(etape(blanc, "STANDING_ORDERS").read()).isEqualTo(3);
        assertThat(etape(blanc, "STANDING_ORDERS").written()).isEqualTo(2);
        assertThat(lire(loyer.id()).dueDate()).isEqualTo(echeance);
        assertThat(lire(loyer.id()).occurrence()).isZero();
        assertThat(lire(decouvert.id()).attempts()).isZero();
        assertThat(solde(payeur)).isEqualTo(xof("100000"));
        assertThat(executions(loyer.id())).isEmpty();

        // Le jour de l'echeance : le virement part frais compris, l'ordre de paiement est depose,
        // et le rejet est enregistre sans faire d'anomalie — un client sans provision n'arrete
        // pas l'arrete de la banque.
        TfjRun run = engine.run(ENTITY, echeance, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(etape(run, "STANDING_ORDERS").read()).isEqualTo(3);
        assertThat(etape(run, "STANDING_ORDERS").written()).isEqualTo(2);
        assertThat(etape(run, "STANDING_ORDERS").anomalies()).isEmpty();

        // Le virement interne : 30 000 vires, 500 de frais, 90 de taxe.
        assertThat(solde(beneficiaire)).isEqualTo(xof("30000"));
        StandingOrderService.Execution vire = derniere(loyer.id());
        assertThat(vire.outcome()).isEqualTo(StandingOrderService.Outcome.EXECUTED);
        assertThat(vire.amount()).isEqualTo(xof("30000"));
        assertThat(vire.fee()).isEqualTo(xof("500"));
        assertThat(vire.tax()).isEqualTo(xof("90"));
        assertThat(vire.entryId()).isNotNull();
        // L'echeance suivante se calcule depuis la date de debut, pas de proche en proche.
        StandingOrderService.StandingOrder apres = lire(loyer.id());
        assertThat(apres.occurrence()).isEqualTo(1);
        assertThat(apres.dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(apres.attempts()).isZero();
        assertThat(apres.status()).isEqualTo("ACTIVE");

        // L'ordre externe : la banque n'a pas vire, elle a depose un ordre de paiement.
        StandingOrderService.Execution depose = derniere(pension.id());
        assertThat(depose.outcome()).isEqualTo(StandingOrderService.Outcome.EXECUTED);
        assertThat(depose.paymentOrderId()).isNotNull();
        PaymentService.PaymentOrder paiement = paiement(depose.paymentOrderId());
        assertThat(paiement.status()).isEqualTo("ORDERED");
        assertThat(paiement.amount()).isEqualTo(xof("20000"));
        assertThat(solde(reglement)).isEqualTo(xof("20000"));
        // 100 000 − (30 000 + 500 + 90) − (20 000 + 1 000 + 180)
        assertThat(solde(payeur)).isEqualTo(xof("48230"));

        // Le rejet : enregistre, l'echeance ne bouge pas, la tentative se refait demain.
        StandingOrderService.Execution rejet = derniere(decouvert.id());
        assertThat(rejet.outcome()).isEqualTo(StandingOrderService.Outcome.REJECTED);
        assertThat(rejet.reason()).isEqualTo("SANS_PROVISION");
        StandingOrderService.StandingOrder retente = lire(decouvert.id());
        assertThat(retente.attempts()).isEqualTo(1);
        assertThat(retente.dueDate()).isEqualTo(echeance);
        assertThat(retente.nextAttemptDate()).isEqualTo(echeance.plusDays(1));
        assertThat(solde(pauvre)).isEqualTo(xof("100"));

        // L'annulation contre-passe les ecritures, solde l'ordre de paiement depose et rend
        // chaque ordre a l'echeance que l'arrete a trouvee, sans tentative consommee.
        engine.cancel(run.id(), ACTOR, echeance.plusDays(1), "erreur de parametrage");
        StandingOrderService.StandingOrder rendu = lire(loyer.id());
        assertThat(rendu.occurrence()).isZero();
        assertThat(rendu.dueDate()).isEqualTo(echeance);
        assertThat(rendu.nextAttemptDate()).isEqualTo(echeance);
        assertThat(rendu.attempts()).isZero();
        assertThat(lire(decouvert.id()).attempts()).isZero();
        assertThat(lire(decouvert.id()).nextAttemptDate()).isEqualTo(echeance);
        assertThat(executions(loyer.id())).isEmpty();
        assertThat(paiement(depose.paymentOrderId()).status()).isEqualTo("CANCELLED");
        assertThat(solde(payeur)).isEqualTo(xof("100000"));
        assertThat(solde(beneficiaire).isZero()).isTrue();
        assertThat(solde(reglement).isZero()).isTrue();

        // Rejoue, la journee les execute de nouveau, sous ses propres cles.
        TfjRun rejeu = engine.run(ENTITY, echeance, ACTOR, RunMode.REAL);
        assertThat(rejeu.isCompleted()).as(rejeu.summary()).isTrue();
        assertThat(lire(loyer.id()).occurrence()).isEqualTo(1);
        assertThat(solde(payeur)).isEqualTo(xof("48230"));
        assertThat(solde(beneficiaire)).isEqualTo(xof("30000"));

        // Envoye depuis, l'ordre de paiement engage la banque : l'arrete ne s'annule plus, et il
        // le dit avant que rien ne soit defait.
        UUID envoye = derniere(pension.id()).paymentOrderId();
        PaymentService payments = new PaymentService(database, postingService);
        payments.send(envoye, ACTOR);
        payments.settle(envoye, nostro.id(), ACTOR);
        assertThatThrownBy(() -> engine.cancel(rejeu.id(), ACTOR, echeance.plusDays(1), "essai"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("ne s'annule plus");
        assertThat(lire(loyer.id()).occurrence()).as("rien n'a ete defait").isEqualTo(1);
        assertThat(solde(beneficiaire)).isEqualTo(xof("30000"));
    }

    // ------------------------------------------------------------------ outillage

    private static void regle(java.sql.Connection c, String type, Direction direction) {
        Calendars.addRule(c, ENTITY, new ValueDateRule(type, null, direction, 0,
                                                       OffsetUnit.CALENDAR_DAYS,
                                                       BusinessDayConvention.UNADJUSTED,
                                                       LocalDate.of(2020, 1, 1), null),
                          ACTOR, APPROVER);
    }

    private static void produit(String code, Map<String, String> parametres) {
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, "CURRENT_ACCOUNT", code, "XOF", J1.minusYears(2), null,
                new LinkedHashMap<>(parametres), List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    private static Account compteClient(String code, LocalDate jour, UUID titulaire) {
        Account account = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, account.id(), "CC-OP", jour.minusMonths(1), null);
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER,
                                  jour.minusMonths(1), ACTOR);
            return null;
        });
        return account;
    }

    private static StandingOrderService.StandingOrder lire(UUID id) {
        return database.inTransaction(c -> StandingOrderService.require(c, id));
    }

    private static List<StandingOrderService.Execution> executions(UUID id) {
        return database.inTransaction(c -> StandingOrderService.executions(c, id));
    }

    private static StandingOrderService.Execution derniere(UUID id) {
        return executions(id).getFirst();
    }

    private static PaymentService.PaymentOrder paiement(UUID id) {
        return database.inTransaction(c -> PaymentService.require(c, id));
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
