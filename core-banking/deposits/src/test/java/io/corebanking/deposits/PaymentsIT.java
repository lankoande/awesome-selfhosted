package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Reconciliation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les paiements sortants : debites a l'ordre, envoyes, regles ou retournes, annules avant envoi. */
class PaymentsIT extends DepositsTestBase {

    private static PaymentService.Order ordre(Decor decor, UUID compte, String montant, String key) {
        return new PaymentService.Order(IdempotencyKey.of(key), decor.entityId(), compte,
                                        xof(montant), "Fournisseur SA", "BK-CI-001",
                                        "CI93CI0010001234567890123456", "Facture 42", null, ACTOR);
    }

    @Test
    @DisplayName("un ordre debite le client a l'ordre, sur le compte de reglement ; envoye puis regle sur le nostro ; un rejeu rend le meme ordre")
    void order_send_settle() {
        Decor decor = decor("PAY");
        Account reglement = account(decor.entityId(), "PAY-REGLEMENT-SORTANT", AccountKind.GL,
                                    NormalBalance.CREDIT);
        Account nostro = account(decor.entityId(), "PAY-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_PAYMENT_FEE, "1000");
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        produit(decor, "CC-PAY", "CURRENT_ACCOUNT", parametres);
        UUID compte = ouvrir(decor, "CLI-PAY", "CC-PAY", client(decor.entityId(), "T-PAY"));
        verser(decor, compte, "500000", "pay-0");

        PaymentService.Placed place = payments.order(ordre(decor, compte, "200000", "pay-1"));
        PaymentService.PaymentOrder ordre = place.order();
        assertThat(place.replayed()).isFalse();
        assertThat(ordre.status()).isEqualTo("ORDERED");
        assertThat(ordre.fee()).isEqualTo(xof("1000"));
        assertThat(ordre.tax()).isEqualTo(xof("180"));
        assertThat(solde(compte)).isEqualTo(xof("298820"));
        assertThat(solde(reglement)).isEqualTo(xof("200000"));
        assertThat(payments.order(ordre(decor, compte, "200000", "pay-1")).replayed()).isTrue();
        assertThat(solde(compte)).isEqualTo(xof("298820"));

        // Avant envoi, ni reglement ni retour ; apres envoi, plus d'annulation.
        assertThatThrownBy(() -> payments.settle(ordre.id(), nostro.id(), ACTOR))
            .isInstanceOf(PaymentService.PaymentStateException.class);
        assertThatThrownBy(() -> payments.returnOrder(ordre.id(), "trop tot", ACTOR))
            .isInstanceOf(PaymentService.PaymentStateException.class);
        assertThat(payments.send(ordre.id(), ACTOR).status()).isEqualTo("SENT");
        assertThatThrownBy(() -> payments.cancel(ordre.id(), "trop tard", ACTOR))
            .isInstanceOf(PaymentService.PaymentStateException.class);

        // Le reglement se fait sur un nostro de l'entite, en devise.
        assertThatThrownBy(() -> payments.settle(ordre.id(), decor.caisse().id(), ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nostro");
        PaymentService.PaymentOrder regle = payments.settle(ordre.id(), nostro.id(), ACTOR);
        assertThat(regle.status()).isEqualTo("SETTLED");
        assertThat(regle.settlementEntryId()).isNotNull();
        assertThat(solde(reglement).isZero()).isTrue();
        assertThat(solde(nostro)).isEqualTo(xof("-200000"));
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();

        // Regle, il peut encore revenir : le montant revient au client depuis le nostro, les
        // frais restent acquis.
        PaymentService.PaymentOrder retour = payments.returnOrder(regle.id(), "compte inconnu",
                                                                  ACTOR);
        assertThat(retour.status()).isEqualTo("RETURNED");
        assertThat(retour.returnReason()).isEqualTo("compte inconnu");
        assertThat(solde(compte)).isEqualTo(xof("498820"));
        assertThat(solde(nostro).isZero()).isTrue();
        long retournes = database.inTransaction(
            c -> PaymentService.count(c, decor.entityId(), "RETURNED"));
        assertThat(retournes).isEqualTo(1L);
        List<PaymentService.PaymentOrder> tous = database.inTransaction(
            c -> PaymentService.page(c, decor.entityId(), null, 0, 10));
        assertThat(tous).hasSize(1);
    }

    @Test
    @DisplayName("un ordre s'annule avant envoi par contre-passation, frais compris ; le produit sans compte de reglement n'admet pas de paiement ; le plafond s'applique")
    void cancel_and_refusals() {
        Decor decor = decor("PAC");
        Account reglement = account(decor.entityId(), "PAC-REGLEMENT-SORTANT", AccountKind.GL,
                                    NormalBalance.CREDIT);
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_PAYMENT_FEE, "1000");
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        parametres.put(DepositCatalog.P_TRANSACTION_MAX, "300000");
        produit(decor, "CC-PAC", "CURRENT_ACCOUNT", parametres);
        produit(decor, "EP-PAC", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-PAC", "CC-PAC", client(decor.entityId(), "T-PAC"));
        UUID epargne = ouvrir(decor, "CLI-PAC-EP", "EP-PAC", client(decor.entityId(), "T-PAC2"));
        verser(decor, compte, "500000", "pac-0");
        verser(decor, epargne, "500000", "pac-1");

        assertThatThrownBy(() -> payments.order(ordre(decor, epargne, "1000", "pac-2")))
            .isInstanceOf(PaymentService.NotAllowedException.class)
            .hasMessageContaining("paiement sortant");
        assertThatThrownBy(() -> payments.order(ordre(decor, compte, "300001", "pac-3")))
            .isInstanceOf(Limits.LimitExceededException.class);
        assertThatThrownBy(() -> payments.order(new PaymentService.Order(
                IdempotencyKey.of("pac-4"), decor.entityId(), compte, xof("1000"), " ", "BK",
                "CI93", null, null, ACTOR)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beneficiaire");

        PaymentService.PaymentOrder ordre = payments.order(ordre(decor, compte, "100000", "pac-5"))
            .order();
        assertThat(solde(compte)).isEqualTo(xof("398820"));
        assertThatThrownBy(() -> payments.cancel(ordre.id(), " ", ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        PaymentService.PaymentOrder annule = payments.cancel(ordre.id(), "erreur de saisie", ACTOR);
        assertThat(annule.status()).isEqualTo("CANCELLED");
        assertThat(annule.cancelEntryId()).isNotNull();
        assertThat(solde(compte)).isEqualTo(xof("500000"));
        assertThat(solde(reglement).isZero()).isTrue();
        assertThatThrownBy(() -> payments.send(ordre.id(), ACTOR))
            .isInstanceOf(PaymentService.PaymentStateException.class);
        assertThatThrownBy(() -> payments.send(UUID.randomUUID(), ACTOR))
            .isInstanceOf(PaymentService.UnknownPaymentOrderException.class);
    }

    @Test
    @DisplayName("l'ordre d'un client d'agence passe par la liaison : le compte de reglement est tenu au siege, le frais reste a l'agence")
    void the_clearing_account_is_held_at_head_office() {
        Decor decor = decor("PAB");
        Account reglement = account(decor.entityId(), "PAB-REGLEMENT-SORTANT", AccountKind.GL,
                                    NormalBalance.CREDIT);
        Account liaison = account(decor.entityId(), "PAB-LIAISON-A", AccountKind.GL,
                                  NormalBalance.DEBIT);
        UUID siege = siege(decor);
        UUID agence = database.inTransaction(c -> io.corebanking.ledger.store.Branches.create(
            c, decor.entityId(), "A", "Agence A", io.corebanking.ledger.store.Branches.Kind.BRANCH,
            null, J.minusMonths(1), Map.of(Currencies.XOF, liaison.id())));
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_PAYMENT_FEE, "1000");
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        produit(decor, "CC-PAB", "CURRENT_ACCOUNT", parametres);
        UUID compte = ouvrir(decor, "CLI-PAB", "CC-PAB", client(decor.entityId(), "T-PAB"), agence);
        Account caisseA = account(decor.entityId(), "PAB-CAISSE-A", AccountKind.INTERNAL,
                                  NormalBalance.DEBIT);
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement("UPDATE account SET branch_id = ? WHERE id = ?")) {
                ps.setObject(1, agence);
                ps.setObject(2, caisseA.id());
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Caisse d'agence", e);
            }
            return null;
        });
        operations.deposit(new OperationsService.Deposit(IdempotencyKey.of("pab-0"),
            decor.entityId(), compte, caisseA.id(), xof("500000"), null, "versement", ACTOR));

        PaymentService.PaymentOrder ordre = payments.order(ordre(decor, compte, "200000", "pab-1"))
            .order();
        Map<UUID, List<Object[]>> lignes = lignes(ordre.entryId());
        assertThat(lignes.get(reglement.id())).singleElement()
            .satisfies(l -> assertThat(l[0]).as("le reglement au siege").isEqualTo(siege));
        assertThat(lignes.get(decor.produitsFrais().id())).singleElement()
            .satisfies(l -> assertThat(l[0]).as("le frais a l'agence").isEqualTo(agence));
        assertThat(lignes.get(liaison.id())).as("la liaison relie l'agence au siege").isNotEmpty();
        List<io.corebanking.ledger.store.Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> io.corebanking.ledger.store.Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    /** Par compte, les lignes de l'ecriture : agence comptable et nature. */
    private static Map<UUID, List<Object[]>> lignes(UUID entryId) {
        return database.inTransaction(c -> {
            Map<UUID, List<Object[]>> byAccount = new HashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, branch_id, kind FROM journal_line WHERE entry_id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byAccount.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>())
                            .add(new Object[] {rs.getObject(2, UUID.class), rs.getString(3)});
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
            return byAccount;
        });
    }
}
