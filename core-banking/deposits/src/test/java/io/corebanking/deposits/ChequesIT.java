package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Reconciliation;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les cheques : chequiers delivres a deux aux frais du produit, paiement d'un cheque emis au
 * guichet ou par compensation, opposition, incident sans provision, remises creditees sauf bonne
 * fin.
 */
class ChequesIT extends DepositsTestBase {

    /** Compte de cheques a l'encaissement : un actif du siege. */
    private static Account encaissement(Decor decor, String code) {
        return account(decor.entityId(), code + "-ENCAISSEMENT", AccountKind.GL, NormalBalance.DEBIT);
    }

    private static Map<String, String> chequier(Decor decor, Account encaissement) {
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_CHEQUE_BOOK_FEE, "2000");
        parametres.put(DepositCatalog.P_CHEQUE_COLLECTION, encaissement.id().toString());
        return parametres;
    }

    private static ChequeService.Book chequier(Decor decor, UUID compte, int nombre) {
        return cheques.issueBook(new ChequeService.BookIssue(decor.entityId(), compte, nombre,
                                                             ACTOR, APPROVER));
    }

    private static ChequeService.Payment guichet(Decor decor, UUID compte, long numero,
                                                 String montant, String key) {
        return new ChequeService.Payment(IdempotencyKey.of(key), decor.entityId(), compte, numero,
                                         xof(montant), ChequeService.PaymentMode.CASH,
                                         decor.caisse().id(), "Porteur " + numero, null, ACTOR);
    }

    private static ChequeService.Payment compensation(Decor decor, UUID compte, long numero,
                                                      String montant, UUID nostro, String key) {
        return new ChequeService.Payment(IdempotencyKey.of(key), decor.entityId(), compte, numero,
                                         xof(montant), ChequeService.PaymentMode.CLEARING, nostro,
                                         "Banque presentatrice", null, ACTOR);
    }

    private static ChequeService.Deposit remise(Decor decor, UUID compte, String montant,
                                                String numero, String key) {
        return new ChequeService.Deposit(IdempotencyKey.of(key), decor.entityId(), compte,
                                         xof(montant), "BK-CI-002", numero, "Tireur SARL", null,
                                         ACTOR);
    }

    private static ChequeService.Cheque cheque(UUID compte, long numero) {
        return database.inTransaction(c -> ChequeService.cheque(c, compte, numero)).orElseThrow();
    }

    @Test
    @DisplayName("un chequier se delivre a deux, aux frais du produit ; ses numeros suivent le chequier precedent ; un produit sans frais le delivre gratuitement")
    void books() {
        Decor decor = decor("CHQ");
        produit(decor, "CC-CHQ", "CURRENT_ACCOUNT", chequier(decor, encaissement(decor, "CHQ")));
        produit(decor, "CC-CHQ0", "CURRENT_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-CHQ", "CC-CHQ", client(decor.entityId(), "T-CHQ"));
        UUID gratuit = ouvrir(decor, "CLI-CHQ0", "CC-CHQ0", client(decor.entityId(), "T-CHQ0"));
        verser(decor, compte, "500000", "chq-0");
        verser(decor, gratuit, "10000", "chq-1");

        ChequeService.Book premier = chequier(decor, compte, 25);
        assertThat(premier.firstNumber()).isEqualTo(1L);
        assertThat(premier.lastNumber()).isEqualTo(25L);
        assertThat(premier.status()).isEqualTo("ACTIVE");
        assertThat(premier.deliveredOn()).isEqualTo(J);
        assertThat(premier.fee()).as("frais 2000 et taxe 18 %").isEqualTo(xof("2360"));
        assertThat(premier.feeEntryId()).isNotNull();
        assertThat(solde(compte)).isEqualTo(xof("497640"));
        assertThat(solde(decor.produitsFrais())).isEqualTo(xof("2000"));
        assertThat(solde(decor.taxe())).isEqualTo(xof("360"));

        ChequeService.Book second = chequier(decor, compte, 25);
        assertThat(second.firstNumber()).isEqualTo(26L);
        assertThat(second.lastNumber()).isEqualTo(50L);
        List<ChequeService.Book> chequiers = database.inTransaction(
            c -> ChequeService.books(c, compte));
        assertThat(chequiers).extracting(ChequeService.Book::id)
            .containsExactly(premier.id(), second.id());
        List<ChequeService.Cheque> vierges = database.inTransaction(
            c -> ChequeService.cheques(c, compte, "UNUSED"));
        assertThat(vierges).hasSize(50);
        assertThat(vierges.get(49).number()).isEqualTo(50L);

        ChequeService.Book sansFrais = chequier(decor, gratuit, 10);
        assertThat(sansFrais.fee().isZero()).isTrue();
        assertThat(sansFrais.feeEntryId()).isNull();
        assertThat(solde(gratuit)).isEqualTo(xof("10000"));

        // A deux, et de 1 a 200 cheques.
        assertThatThrownBy(() -> new ChequeService.BookIssue(decor.entityId(), compte, 25, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThatThrownBy(() -> new ChequeService.BookIssue(decor.entityId(), compte, 0, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChequeService.BookIssue(decor.entityId(), compte, 201, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class);
        // Le frais se preleve sur un compte qui peut le payer.
        UUID vide = ouvrir(decor, "CLI-CHQ-VIDE", "CC-CHQ", client(decor.entityId(), "T-CHQV"));
        assertThatThrownBy(() -> chequier(decor, vide, 25))
            .isInstanceOf(io.corebanking.ledger.domain.error.InsufficientFundsException.class);
        List<ChequeService.Book> aucun = database.inTransaction(c -> ChequeService.books(c, vide));
        assertThat(aucun).isEmpty();
    }

    @Test
    @DisplayName("un cheque emis se paie une fois, au guichet ou par compensation ; frappe d'opposition il ne se paie plus ; sans provision il est rejete et l'incident reste, puis il peut etre represente")
    void pay_stop_reject() {
        Decor decor = decor("CHP");
        Account nostro = account(decor.entityId(), "CHP-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        produit(decor, "CC-CHP", "CURRENT_ACCOUNT", chequier(decor, encaissement(decor, "CHP")));
        UUID compte = ouvrir(decor, "CLI-CHP", "CC-CHP", client(decor.entityId(), "T-CHP"));
        verser(decor, compte, "500000", "chp-0");
        chequier(decor, compte, 10);
        assertThat(solde(compte)).isEqualTo(xof("497640"));

        // Au guichet : le tireur est debite, la caisse sort le montant ; le cheque est paye.
        ChequeService.Paid paye = cheques.pay(guichet(decor, compte, 3, "100000", "chp-1"));
        assertThat(paye.cheque().status()).isEqualTo("PAID");
        assertThat(paye.cheque().amount()).isEqualTo(xof("100000"));
        assertThat(paye.cheque().beneficiary()).isEqualTo("Porteur 3");
        assertThat(paye.cheque().paidOn()).isEqualTo(J);
        assertThat(paye.cheque().entryId()).isEqualTo(paye.receipt().entryId());
        assertThat(paye.receipt().replayed()).isFalse();
        assertThat(paye.receipt().valueDate()).isEqualTo(J);
        assertThat(paye.receipt().balanceAfter()).isEqualTo(xof("397640"));
        assertThat(solde(compte)).isEqualTo(xof("397640"));
        assertThat(solde(decor.caisse())).isEqualTo(xof("400000"));

        // Rejoue avec la meme cle, le paiement rend le premier recu ; sous une autre cle, le
        // cheque est deja paye.
        ChequeService.Paid rejeu = cheques.pay(guichet(decor, compte, 3, "100000", "chp-1"));
        assertThat(rejeu.receipt().replayed()).isTrue();
        assertThat(rejeu.receipt().entryId()).isEqualTo(paye.receipt().entryId());
        assertThat(solde(compte)).isEqualTo(xof("397640"));
        assertThatThrownBy(() -> cheques.pay(guichet(decor, compte, 3, "100000", "chp-2")))
            .isInstanceOf(ChequeService.ChequeStateException.class)
            .hasMessageContaining("deja paye");
        assertThatThrownBy(() -> cheques.pay(guichet(decor, compte, 99, "1000", "chp-3")))
            .isInstanceOf(ChequeService.UnknownChequeException.class);

        // L'opposition : motivee, elle arrete le cheque ; elle n'atteint pas un cheque paye.
        ChequeService.Cheque oppose = cheques.stop(decor.entityId(), compte, 4,
                                                   ChequeService.StopReason.LOSS, ACTOR);
        assertThat(oppose.status()).isEqualTo("STOPPED");
        assertThat(oppose.stopReason()).isEqualTo("LOSS");
        assertThat(oppose.stoppedOn()).isEqualTo(J);
        assertThatThrownBy(() -> cheques.pay(guichet(decor, compte, 4, "1000", "chp-4")))
            .isInstanceOf(ChequeService.ChequeStoppedException.class)
            .hasMessageContaining("opposition");
        assertThat(cheques.stop(decor.entityId(), compte, 4, ChequeService.StopReason.THEFT, ACTOR)
                       .stopReason()).as("une opposition posee ne se reecrit pas").isEqualTo("LOSS");
        assertThatThrownBy(() -> cheques.stop(decor.entityId(), compte, 3,
                                              ChequeService.StopReason.LOSS, ACTOR))
            .isInstanceOf(ChequeService.ChequeStateException.class).hasMessageContaining("tardive");
        // Le cheque n'existe que pour son entite.
        Decor autre = decor("CHP2");
        assertThatThrownBy(() -> cheques.stop(autre.entityId(), compte, 5,
                                              ChequeService.StopReason.LOSS, ACTOR))
            .isInstanceOf(ChequeService.UnknownChequeException.class);
        assertThatThrownBy(() -> cheques.pay(new ChequeService.Payment(
                IdempotencyKey.of("chp-x"), autre.entityId(), compte, 5, xof("1000"),
                ChequeService.PaymentMode.CASH, autre.caisse().id(), "Porteur", null, ACTOR)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autre entite");

        // Sans provision : rejete, l'incident est enregistre bien que rien ne soit ecrit.
        assertThatThrownBy(() -> cheques.pay(guichet(decor, compte, 5, "1000000", "chp-5")))
            .isInstanceOf(ChequeService.ChequeRejectedException.class)
            .hasMessageContaining("SANS_PROVISION");
        List<ChequeService.Incident> incidents = database.inTransaction(
            c -> ChequeService.incidents(c, compte));
        assertThat(incidents).singleElement().satisfies(incident -> {
            assertThat(incident.number()).isEqualTo(5L);
            assertThat(incident.amount()).isEqualTo(xof("1000000"));
            assertThat(incident.reason()).isEqualTo("SANS_PROVISION");
            assertThat(incident.occurredOn()).isEqualTo(J);
            assertThat(incident.presentedBy()).isEqualTo("Porteur 5");
        });
        assertThat(cheque(compte, 5).status()).isEqualTo("REJECTED");
        assertThat(solde(compte)).isEqualTo(xof("397640"));
        // Represente avec la provision, il se paie.
        assertThat(cheques.pay(guichet(decor, compte, 5, "50000", "chp-6")).cheque().status())
            .isEqualTo("PAID");
        assertThat(solde(compte)).isEqualTo(xof("347640"));

        // Par compensation : le nostro sort le montant ; la contrepartie doit etre un nostro,
        // et au guichet un compte interne de l'entite, jamais un compte client.
        ChequeService.Paid compense = cheques.pay(compensation(decor, compte, 6, "20000",
                                                               nostro.id(), "chp-7"));
        assertThat(compense.cheque().status()).isEqualTo("PAID");
        assertThat(compense.receipt().remote()).isFalse();
        assertThat(solde(compte)).isEqualTo(xof("327640"));
        assertThat(solde(nostro)).isEqualTo(xof("-20000"));
        assertThatThrownBy(() -> cheques.pay(compensation(decor, compte, 7, "1000",
                                                          decor.caisse().id(), "chp-8")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nostro");
        UUID tiers = ouvrir(decor, "CLI-CHP-2", "CC-CHP", client(decor.entityId(), "T-CHP3"));
        assertThatThrownBy(() -> cheques.pay(new ChequeService.Payment(
                IdempotencyKey.of("chp-9"), decor.entityId(), compte, 7, xof("1000"),
                ChequeService.PaymentMode.CASH, tiers, "Porteur", null, ACTOR)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caisse");
        assertThat(cheque(compte, 7).status()).isEqualTo("UNUSED");

        List<ChequeService.Cheque> payes = database.inTransaction(
            c -> ChequeService.cheques(c, compte, "PAID"));
        assertThat(payes).extracting(ChequeService.Cheque::number).containsExactly(3L, 5L, 6L);
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    @Test
    @DisplayName("le cheque d'un client d'agence paye au siege passe par la liaison : au guichet dans l'agence de la caisse, par compensation au siege")
    void branch_cheque_is_paid_through_the_bridge() {
        Decor decor = decor("CHB");
        Account liaison = account(decor.entityId(), "CHB-LIAISON-A", AccountKind.GL,
                                  NormalBalance.DEBIT);
        Account nostro = account(decor.entityId(), "CHB-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        UUID siege = siege(decor);
        UUID agence = database.inTransaction(c -> Branches.create(
            c, decor.entityId(), "A", "Agence A", Branches.Kind.BRANCH, null, J.minusMonths(1),
            Map.of(Currencies.XOF, liaison.id())));
        produit(decor, "CC-CHB", "CURRENT_ACCOUNT", chequier(decor, encaissement(decor, "CHB")));
        UUID compte = ouvrir(decor, "CLI-CHB", "CC-CHB", client(decor.entityId(), "T-CHB"), agence);
        verser(decor, compte, "500000", "chb-0");
        chequier(decor, compte, 5);

        ChequeService.Paid guichet = cheques.pay(guichet(decor, compte, 1, "30000", "chb-1"));
        assertThat(guichet.receipt().branchId()).isEqualTo(siege);
        assertThat(guichet.receipt().remote()).as("paye hors de l'agence du compte").isTrue();
        Map<UUID, UUID> agences = agences(guichet.receipt().entryId());
        assertThat(agences.get(decor.caisse().id())).isEqualTo(siege);
        assertThat(agences.get(compte)).isEqualTo(agence);
        assertThat(agences).containsKey(liaison.id());

        ChequeService.Paid compense = cheques.pay(compensation(decor, compte, 2, "20000",
                                                               nostro.id(), "chb-2"));
        agences = agences(compense.receipt().entryId());
        assertThat(agences.get(nostro.id())).as("le nostro au siege").isEqualTo(siege);
        assertThat(agences.get(compte)).isEqualTo(agence);
        assertThat(agences).containsKey(liaison.id());
        assertThat(solde(compte)).isEqualTo(xof("447640"));
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    @Test
    @DisplayName("une remise credite le client sauf bonne fin, a deux jours ouvres de valeur, le montant bloque ; reglee, le blocage tombe et la valeur va au nostro ; impayee, le credit est contre-passe")
    void deposits() {
        Decor decor = decor("CHR");
        Account encaissement = encaissement(decor, "CHR");
        Account nostro = account(decor.entityId(), "CHR-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        produit(decor, "CC-CHR", "CURRENT_ACCOUNT", chequier(decor, encaissement));
        produit(decor, "EP-CHR", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-CHR", "CC-CHR", client(decor.entityId(), "T-CHR"));
        UUID epargne = ouvrir(decor, "CLI-CHR-EP", "EP-CHR", client(decor.entityId(), "T-CHR2"));
        verser(decor, compte, "500000", "chr-0");

        ChequeService.Deposited remise = cheques.deposit(remise(decor, compte, "80000", "0001234",
                                                                "chr-1"));
        assertThat(remise.replayed()).isFalse();
        ChequeService.ChequeDeposit depot = remise.deposit();
        assertThat(depot.status()).isEqualTo("DEPOSITED");
        assertThat(depot.depositedOn()).isEqualTo(J);
        assertThat(depot.valueDate()).as("deux jours ouvres").isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(depot.holdId()).isNotNull();
        assertThat(depot.collectionAccountId()).isEqualTo(encaissement.id());
        assertThat(solde(compte)).isEqualTo(xof("580000"));
        assertThat(disponible(compte, J)).as("credite mais bloque").isEqualTo(xof("500000"));
        assertThat(solde(encaissement)).isEqualTo(xof("80000"));
        List<Holds.Hold> blocages = database.inTransaction(c -> Holds.activeOn(c, compte));
        assertThat(blocages).singleElement().satisfies(hold -> {
            assertThat(hold.id()).isEqualTo(depot.holdId());
            assertThat(hold.type()).isEqualTo(ChequeService.HOLD_TYPE);
            assertThat(hold.amount()).isEqualTo(xof("80000"));
        });
        assertThat(cheques.deposit(remise(decor, compte, "80000", "0001234", "chr-1")).replayed())
            .isTrue();
        assertThat(solde(compte)).isEqualTo(xof("580000"));

        // Reglee par le correspondant : le blocage tombe, l'encaissement se solde sur le nostro.
        assertThatThrownBy(() -> cheques.settleDeposit(depot.id(), decor.caisse().id(), ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nostro");
        ChequeService.ChequeDeposit reglee = cheques.settleDeposit(depot.id(), nostro.id(), ACTOR);
        assertThat(reglee.status()).isEqualTo("SETTLED");
        assertThat(reglee.settledOn()).isEqualTo(J);
        assertThat(reglee.settlementAccountId()).isEqualTo(nostro.id());
        assertThat(reglee.settlementEntryId()).isNotNull();
        assertThat(disponible(compte, J)).isEqualTo(xof("580000"));
        assertThat(solde(encaissement).isZero()).isTrue();
        assertThat(solde(nostro)).isEqualTo(xof("80000"));
        List<Holds.Hold> apresReglement = database.inTransaction(c -> Holds.activeOn(c, compte));
        assertThat(apresReglement).isEmpty();
        assertThatThrownBy(() -> cheques.settleDeposit(depot.id(), nostro.id(), ACTOR))
            .isInstanceOf(ChequeService.ChequeDepositStateException.class);
        assertThatThrownBy(() -> cheques.returnDeposit(depot.id(), "trop tard", ACTOR))
            .isInstanceOf(ChequeService.ChequeDepositStateException.class);

        // Impayee : le credit sauf bonne fin est contre-passe, le blocage tombe avec lui.
        ChequeService.ChequeDeposit seconde = cheques.deposit(
            remise(decor, compte, "30000", "0001235", "chr-2")).deposit();
        assertThat(solde(compte)).isEqualTo(xof("610000"));
        assertThat(disponible(compte, J)).isEqualTo(xof("580000"));
        assertThatThrownBy(() -> cheques.returnDeposit(seconde.id(), " ", ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        ChequeService.ChequeDeposit impayee = cheques.returnDeposit(seconde.id(),
                                                                    "provision insuffisante", ACTOR);
        assertThat(impayee.status()).isEqualTo("RETURNED");
        assertThat(impayee.returnedOn()).isEqualTo(J);
        assertThat(impayee.returnReason()).isEqualTo("provision insuffisante");
        assertThat(impayee.returnEntryId()).isNotNull();
        assertThat(solde(compte)).isEqualTo(xof("580000"));
        assertThat(disponible(compte, J)).isEqualTo(xof("580000"));
        assertThat(solde(encaissement).isZero()).isTrue();
        List<Holds.Hold> apresImpaye = database.inTransaction(c -> Holds.activeOn(c, compte));
        assertThat(apresImpaye).isEmpty();

        // Lecture : par statut, en comptant ; une remise inconnue n'existe pas.
        long reglees = database.inTransaction(
            c -> ChequeService.countDeposits(c, decor.entityId(), "SETTLED"));
        assertThat(reglees).isEqualTo(1L);
        List<ChequeService.ChequeDeposit> toutes = database.inTransaction(
            c -> ChequeService.deposits(c, decor.entityId(), null, 0, 10));
        assertThat(toutes).extracting(ChequeService.ChequeDeposit::id)
            .containsExactlyInAnyOrder(depot.id(), seconde.id());
        assertThatThrownBy(() -> cheques.settleDeposit(UUID.randomUUID(), nostro.id(), ACTOR))
            .isInstanceOf(ChequeService.UnknownChequeDepositException.class);

        // Le produit sans compte d'encaissement n'admet pas de remise ; une remise designe son cheque.
        assertThatThrownBy(() -> cheques.deposit(remise(decor, epargne, "1000", "0001236", "chr-3")))
            .isInstanceOf(PaymentService.NotAllowedException.class)
            .hasMessageContaining("remise de cheque");
        assertThatThrownBy(() -> new ChequeService.Deposit(IdempotencyKey.of("chr-4"),
                decor.entityId(), compte, xof("1000"), "BK", " ", "Tireur", null, ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    /** L'agence comptable de chaque compte dans une ecriture. */
    private static Map<UUID, UUID> agences(UUID entryId) {
        return database.inTransaction(c -> {
            Map<UUID, UUID> byAccount = new HashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, branch_id FROM journal_line WHERE entry_id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byAccount.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
            return byAccount;
        });
    }
}
