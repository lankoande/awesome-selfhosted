package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.calendar.Calendars;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les suspens : ce qui attend le correspondant, son anciennete en jours ouvres, son responsable. */
class SuspenseIT extends DepositsTestBase {

    private static Suspense.Draft politique(Decor decor, Suspense.Kind kind, int jours) {
        return new Suspense.Draft(decor.entityId(), kind, jours, "back-office paiements",
                                  LocalDate.of(2020, 1, 1), null, ACTOR, APPROVER);
    }

    private static List<Suspense.Item> revue(Decor decor, LocalDate on) {
        BusinessCalendar calendrier = Calendars.load(database, decor.entityId()).calendar();
        return database.inTransaction(c -> Suspense.items(c, decor.entityId(), on, calendrier));
    }

    @Test
    @DisplayName("une politique se fixe a deux, par nature, sans chevauchement ; sans politique, un suspens est liste sans retard")
    void policies() {
        Decor decor = decor("SUP");
        Suspense.Policy fixee = database.inTransaction(
            c -> Suspense.setPolicy(c, politique(decor, Suspense.Kind.PAYMENT_ORDER, 2)));
        assertThat(fixee.owner()).isEqualTo("back-office paiements");
        assertThat(fixee.maxBusinessDays()).isEqualTo(2);
        assertThatThrownBy(() -> database.inTransaction(
                c -> Suspense.setPolicy(c, politique(decor, Suspense.Kind.PAYMENT_ORDER, 5))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("couvre deja");
        assertThatThrownBy(() -> new Suspense.Draft(decor.entityId(), Suspense.Kind.PAYMENT_ORDER, 2,
                                                    " ", LocalDate.of(2020, 1, 1), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("responsable");
        assertThatThrownBy(() -> new Suspense.Draft(decor.entityId(), Suspense.Kind.PAYMENT_ORDER, 2,
                                                    "x", LocalDate.of(2020, 1, 1), null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        List<Suspense.Policy> politiques = database.inTransaction(
            c -> Suspense.policies(c, decor.entityId()));
        assertThat(politiques).hasSize(1);
        java.util.Optional<Suspense.Policy> aucune = database.inTransaction(
            c -> Suspense.policyFor(c, decor.entityId(), Suspense.Kind.CHEQUE_DEPOSIT, J));
        assertThat(aucune).isEmpty();
    }

    @Test
    @DisplayName("un ordre non regle, une remise non encaissee, un prelevement non regle, un compte d'attente non solde sont des suspens ; l'anciennete se compte en jours ouvres ; au-dela de la politique, en retard")
    void items_age_in_business_days() {
        Decor decor = decor("SUI");
        Account reglement = account(decor.entityId(), "SUI-REGLEMENT-SORTANT", AccountKind.GL,
                                    NormalBalance.CREDIT);
        Account encaissement = account(decor.entityId(), "SUI-ENCAISSEMENT", AccountKind.GL,
                                       NormalBalance.DEBIT);
        Account attente = account(decor.entityId(), "SUI-ATTENTE", AccountKind.SUSPENSE,
                                  NormalBalance.CREDIT);
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        parametres.put(DepositCatalog.P_CHEQUE_COLLECTION, encaissement.id().toString());
        produit(decor, "CC-SUI", "CURRENT_ACCOUNT", parametres);
        UUID compte = ouvrir(decor, "CLI-SUI", "CC-SUI", client(decor.entityId(), "T-SUI"));
        verser(decor, compte, "500000", "sui-0");
        database.inTransaction(c -> {
            Suspense.setPolicy(c, politique(decor, Suspense.Kind.PAYMENT_ORDER, 2));
            Suspense.setPolicy(c, politique(decor, Suspense.Kind.SUSPENSE_ACCOUNT, 1));
            return null;
        });

        // Mardi 15 : un ordre, une remise, et une ecriture en attente.
        PaymentService.PaymentOrder ordre = payments.order(new PaymentService.Order(
            IdempotencyKey.of("sui-1"), decor.entityId(), compte, xof("100000"), "Fournisseur",
            "BK-CI-001", "CI93CI0010001234567890123456", null, null, ACTOR)).order();
        ChequeService.ChequeDeposit remise = cheques.deposit(new ChequeService.Deposit(
            IdempotencyKey.of("sui-2"), decor.entityId(), compte, xof("40000"), "BK-CI-002",
            "0001", "Tireur", null, ACTOR)).deposit();
        postingService.post(PostingCommand.online(IdempotencyKey.of("sui-3"), decor.entityId(), J,
            "MANUAL", ACTOR, List.of(
                PostingLine.debit(decor.caisse().id(), xof("7000"), J, "a justifier"),
                PostingLine.credit(attente.id(), xof("7000"), J, "a justifier"))));

        List<Suspense.Item> leJour = revue(decor, J);
        assertThat(leJour).extracting(Suspense.Item::kind)
            .containsExactlyInAnyOrder(Suspense.Kind.PAYMENT_ORDER, Suspense.Kind.CHEQUE_DEPOSIT,
                                       Suspense.Kind.SUSPENSE_ACCOUNT);
        assertThat(leJour).allMatch(item -> item.ageBusinessDays() == 0 && !item.overdue());
        Suspense.Item ordreEnSuspens = leJour.stream()
            .filter(i -> i.kind() == Suspense.Kind.PAYMENT_ORDER).findFirst().orElseThrow();
        assertThat(ordreEnSuspens.reference()).isEqualTo(ordre.id());
        assertThat(ordreEnSuspens.accountCode()).isEqualTo("SUI-REGLEMENT-SORTANT");
        assertThat(ordreEnSuspens.amount()).isEqualTo(xof("100000"));
        assertThat(ordreEnSuspens.owner()).isEqualTo("back-office paiements");
        assertThat(ordreEnSuspens.maxBusinessDays()).isEqualTo(2);
        Suspense.Item remiseEnSuspens = leJour.stream()
            .filter(i -> i.kind() == Suspense.Kind.CHEQUE_DEPOSIT).findFirst().orElseThrow();
        assertThat(remiseEnSuspens.reference()).isEqualTo(remise.id());
        assertThat(remiseEnSuspens.owner()).as("sans politique, pas de responsable").isNull();
        assertThat(remiseEnSuspens.maxBusinessDays()).isNull();

        // Jeudi 17 : deux jours ouvres — l'ordre est a la limite, le compte d'attente au-dela.
        List<Suspense.Item> jeudi = revue(decor, LocalDate.of(2026, 9, 17));
        assertThat(jeudi.stream().filter(i -> i.kind() == Suspense.Kind.PAYMENT_ORDER).findFirst()
                       .orElseThrow())
            .satisfies(i -> {
                assertThat(i.ageBusinessDays()).isEqualTo(2);
                assertThat(i.overdue()).isFalse();
            });
        assertThat(jeudi.stream().filter(i -> i.kind() == Suspense.Kind.SUSPENSE_ACCOUNT).findFirst()
                       .orElseThrow())
            .satisfies(i -> {
                assertThat(i.ageBusinessDays()).isEqualTo(2);
                assertThat(i.overdue()).isTrue();
                assertThat(i.amount()).isEqualTo(xof("7000"));
            });
        // Lundi 21 : le week-end ne compte pas — quatre jours ouvres — et l'ordre est en retard.
        List<Suspense.Item> lundi = revue(decor, LocalDate.of(2026, 9, 21));
        assertThat(lundi.stream().filter(i -> i.kind() == Suspense.Kind.PAYMENT_ORDER).findFirst()
                       .orElseThrow())
            .satisfies(i -> {
                assertThat(i.ageBusinessDays()).isEqualTo(4);
                assertThat(i.overdue()).isTrue();
            });
        assertThat(lundi.stream().filter(i -> i.kind() == Suspense.Kind.CHEQUE_DEPOSIT).findFirst()
                       .orElseThrow().overdue()).as("sans politique, jamais en retard").isFalse();
        BusinessCalendar calendrier = Calendars.load(database, decor.entityId()).calendar();
        List<Suspense.Item> bloquants = database.inTransaction(c -> Suspense.blockingSuspenseAccounts(
            c, decor.entityId(), LocalDate.of(2026, 9, 21), calendrier));
        assertThat(bloquants).singleElement().satisfies(i -> assertThat(i.accountCode()).isEqualTo("SUI-ATTENTE"));
        List<Suspense.Item> leJourBloquants = database.inTransaction(c -> Suspense.blockingSuspenseAccounts(
            c, decor.entityId(), J, calendrier));
        assertThat(leJourBloquants).as("le jour meme, dans la tolerance").isEmpty();

        // Regle, l'ordre sort des suspens ; l'attente soldee aussi.
        Account nostro = account(decor.entityId(), "SUI-NOSTRO", AccountKind.NOSTRO, NormalBalance.DEBIT);
        payments.send(ordre.id(), ACTOR);
        payments.settle(ordre.id(), nostro.id(), ACTOR);
        postingService.post(PostingCommand.online(IdempotencyKey.of("sui-4"), decor.entityId(), J,
            "MANUAL", ACTOR, List.of(
                PostingLine.debit(attente.id(), xof("7000"), J, "justifie"),
                PostingLine.credit(decor.caisse().id(), xof("7000"), J, "justifie"))));
        assertThat(revue(decor, J)).extracting(Suspense.Item::kind)
            .containsExactly(Suspense.Kind.CHEQUE_DEPOSIT);
    }
}
