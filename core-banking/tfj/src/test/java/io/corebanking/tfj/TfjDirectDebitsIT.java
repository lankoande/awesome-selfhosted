package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.calendar.BusinessDayConvention;
import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.OffsetUnit;
import io.corebanking.calendar.ValueDateRule;
import io.corebanking.deposits.DepositCatalog;
import io.corebanking.deposits.DirectDebitService;
import io.corebanking.deposits.Holds;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
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

/** L'arrete execute les prelevements a l'echeance ; a blanc il ne laisse rien ; annule, il les rend a l'attente. */
class TfjDirectDebitsIT extends TfjTestBase {

    @Test
    @DisplayName("a l'echeance, l'arrete debite le debiteur, credite le creancier sauf bonne fin et rejette le prelevement sans provision ; a blanc, rien ne reste ; annule, tout revient a l'attente et se rejoue ; regle depuis, il ne s'annule plus")
    void direct_debits_follow_the_day() {
        LocalDate jour = businessDate();                                 // 14 septembre 2026
        LocalDate echeance = jour.plusDays(1);
        Account caisse = account("CAISSE-PRL", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-PRL", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-PRL", AccountKind.GL, NormalBalance.CREDIT);
        Account frais = account("FRAIS-PRL", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("TAXE-PRL", AccountKind.GL, NormalBalance.CREDIT);
        Account reglement = account("REGLEMENT-PRL", AccountKind.GL, NormalBalance.CREDIT);
        Account encaissement = account("ENCAISSEMENT-PRL", AccountKind.GL, NormalBalance.DEBIT);
        Account nostro = account("NOSTRO-PRL", AccountKind.NOSTRO, NormalBalance.DEBIT);
        database.inTransaction(c -> {
            regle(c, "DIRECT_DEBIT", Direction.DEBIT);
            regle(c, "DIRECT_DEBIT", Direction.CREDIT);
            regle(c, "DIRECT_DEBIT_ISSUE", Direction.CREDIT);
            return null;
        });
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "6");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());
        parametres.put(DepositCatalog.P_FEE_INCOME, frais.id().toString());
        parametres.put(DepositCatalog.P_TAX_RATE, "18");
        parametres.put(DepositCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        parametres.put(DepositCatalog.P_DIRECT_DEBIT_COLLECTION, encaissement.id().toString());
        parametres.put(DepositCatalog.P_DIRECT_DEBIT_FEE, "500");
        produit("CC-PRL", parametres);
        // Un titulaire verifie repond de chaque compte : sans lui, rien n'y opere.
        PartyService parties = new PartyService(database, Screening.NONE);
        UUID titulaire = parties.create(new PartyService.Draft(
            ENTITY, "T-PRL", PartyKind.NATURAL_PERSON, "Client PRL", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-PRL")), ACTOR));
        parties.verifyKyc(titulaire, RiskRating.MEDIUM, jour.minusMonths(1), ACTOR, APPROVER);
        Account debiteur = compteClient("CLI-PRL-DEB", jour, titulaire);
        Account pauvre = compteClient("CLI-PRL-PAUVRE", jour, titulaire);
        Account creancier = compteClient("CLI-PRL-CRE", jour, titulaire);
        deposit(debiteur, caisse, "100000", jour, "prl-0");
        deposit(pauvre, caisse, "100", jour, "prl-1");
        deposit(creancier, caisse, "5000", jour, "prl-2");

        DirectDebitService service = new DirectDebitService(database, postingService);
        UUID mandat = service.registerMandate(mandat(debiteur, "RUM-1")).id();
        UUID mandatPauvre = service.registerMandate(mandat(pauvre, "RUM-2")).id();
        DirectDebitService.DirectDebit recu = service.present(new DirectDebitService.Presentation(
            IdempotencyKey.of("prl-3"), ENTITY, mandat, xof("30000"), echeance, "FACT-1", null,
            ACTOR)).directDebit();
        DirectDebitService.DirectDebit sansProvision = service.present(
            new DirectDebitService.Presentation(IdempotencyKey.of("prl-4"), ENTITY, mandatPauvre,
                                                xof("1000"), echeance, "FACT-2", null, ACTOR))
            .directDebit();
        DirectDebitService.DirectDebit emis = service.issue(new DirectDebitService.Issue(
            IdempotencyKey.of("prl-5"), ENTITY, creancier.id(), xof("20000"), echeance, "Abonne",
            "BK-CI-003", "CI93CI0030001111222233334444", "RUM-X", "ECH-1", null, ACTOR))
            .directDebit();
        assertThat(List.of(recu, sansProvision, emis))
            .allMatch(dd -> "PENDING".equals(dd.status()));

        // La journee de la presentation ne les touche pas : l'echeance est demain.
        TfjRun veille = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(veille.isCompleted()).as(veille.summary()).isTrue();
        assertThat(etape(veille, "DIRECT_DEBITS").read()).isZero();
        assertThat(businessDate()).isEqualTo(echeance);
        assertThat(solde(debiteur)).isEqualTo(xof("100000"));

        // A blanc : les trois s'executent, et rien ne reste.
        TfjRun blanc = engine.run(ENTITY, echeance, ACTOR, RunMode.DRY_RUN);
        assertThat(blanc.isCompleted()).as(blanc.summary()).isTrue();
        assertThat(etape(blanc, "DIRECT_DEBITS").read()).isEqualTo(3);
        assertThat(etape(blanc, "DIRECT_DEBITS").written()).isEqualTo(3);
        assertThat(lire(recu.id()).status()).isEqualTo("PENDING");
        assertThat(lire(sansProvision.id()).status()).isEqualTo("PENDING");
        assertThat(lire(emis.id()).status()).isEqualTo("PENDING");
        assertThat(solde(debiteur)).isEqualTo(xof("100000"));
        assertThat(solde(creancier)).isEqualTo(xof("5000"));
        assertThat(businessDate()).isEqualTo(echeance);

        // Le jour de l'echeance : debite frais compris, rejete, credite sauf bonne fin.
        TfjRun run = engine.run(ENTITY, echeance, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(etape(run, "DIRECT_DEBITS").written()).isEqualTo(3);
        assertThat(etape(run, "DIRECT_DEBITS").anomalies()).isEmpty();
        DirectDebitService.DirectDebit debite = lire(recu.id());
        assertThat(debite.status()).isEqualTo("COLLECTED");
        assertThat(debite.executedOn()).isEqualTo(echeance);
        assertThat(debite.executedRunId()).isEqualTo(run.id());
        assertThat(solde(debiteur)).isEqualTo(xof("69410"));
        assertThat(solde(reglement)).isEqualTo(xof("30000"));
        DirectDebitService.DirectDebit rejete = lire(sansProvision.id());
        assertThat(rejete.status()).isEqualTo("REJECTED");
        assertThat(rejete.rejectionReason()).isEqualTo("SANS_PROVISION");
        assertThat(rejete.executedRunId()).isEqualTo(run.id());
        assertThat(solde(pauvre)).isEqualTo(xof("100"));
        DirectDebitService.DirectDebit credite = lire(emis.id());
        assertThat(credite.status()).isEqualTo("COLLECTED");
        assertThat(credite.holdId()).isNotNull();
        assertThat(solde(creancier)).isEqualTo(xof("24410"));
        assertThat(disponible(creancier, echeance)).isEqualTo(xof("4410"));
        assertThat(solde(encaissement)).isEqualTo(xof("20000"));

        // L'annulation contre-passe les ecritures, leve le blocage et rend les trois a l'attente.
        engine.cancel(run.id(), ACTOR, echeance.plusDays(1), "erreur de parametrage");
        assertThat(lire(recu.id()).status()).isEqualTo("PENDING");
        assertThat(lire(recu.id()).entryId()).isNull();
        assertThat(lire(sansProvision.id()).status()).isEqualTo("PENDING");
        assertThat(lire(sansProvision.id()).rejectionReason()).isNull();
        assertThat(lire(emis.id()).status()).isEqualTo("PENDING");
        assertThat(lire(emis.id()).holdId()).isNull();
        assertThat(solde(debiteur)).isEqualTo(xof("100000"));
        assertThat(solde(reglement).isZero()).isTrue();
        assertThat(solde(creancier)).isEqualTo(xof("5000"));
        assertThat(solde(encaissement).isZero()).isTrue();
        List<Holds.Hold> blocages = database.inTransaction(c -> Holds.activeOn(c, creancier.id()));
        assertThat(blocages).isEmpty();
        assertThat(businessDate()).isEqualTo(echeance);

        // Rejoue, la journee les execute de nouveau, sous ses propres cles.
        TfjRun rejeu = engine.run(ENTITY, echeance, ACTOR, RunMode.REAL);
        assertThat(rejeu.isCompleted()).as(rejeu.summary()).isTrue();
        assertThat(lire(recu.id()).status()).isEqualTo("COLLECTED");
        assertThat(lire(recu.id()).executedRunId()).isEqualTo(rejeu.id());
        assertThat(lire(sansProvision.id()).status()).isEqualTo("REJECTED");
        assertThat(solde(debiteur)).isEqualTo(xof("69410"));
        assertThat(solde(creancier)).isEqualTo(xof("24410"));

        // Regle depuis, le traitement ne s'annule plus — et il le dit avant de rien defaire.
        service.settle(recu.id(), nostro.id(), ACTOR);
        assertThatThrownBy(() -> engine.cancel(rejeu.id(), ACTOR, echeance.plusDays(1), "essai"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("ne s'annule plus");
        assertThat(lire(emis.id()).status()).as("rien n'a ete defait").isEqualTo("COLLECTED");
        assertThat(solde(debiteur)).isEqualTo(xof("69410"));
        assertThat(solde(reglement).isZero()).isTrue();
        assertThat(solde(nostro)).isEqualTo(xof("-30000"));
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
            ProductCatalog.assignProduct(c, account.id(), "CC-PRL", jour.minusMonths(1), null);
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER,
                                  jour.minusMonths(1), ACTOR);
            return null;
        });
        return account;
    }

    private static DirectDebitService.MandateDraft mandat(Account debiteur, String reference) {
        return new DirectDebitService.MandateDraft(ENTITY, debiteur.id(), reference, "CI-EAU",
            "Compagnie des eaux", null, "BK-CI-002", "CI93CI0020009876543210987654",
            J1.minusMonths(1), J1.minusMonths(1), null, null, ACTOR, APPROVER);
    }

    private static DirectDebitService.DirectDebit lire(UUID id) {
        return database.inTransaction(c -> DirectDebitService.require(c, id));
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static Money disponible(Account account, LocalDate at) {
        return database.inTransaction(c -> Balances.available(c, account.id(), at));
    }

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static TfjRun.StepExecution etape(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow();
    }
}
