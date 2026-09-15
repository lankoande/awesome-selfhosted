package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.party.PartyService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Versement, retrait, virement : dates de valeur calculees, frais du produit, rejeu, refus. */
class OperationsIT extends DepositsTestBase {

    @Test
    @DisplayName("un retrait preleve le frais et sa taxe du produit dans la meme ecriture")
    void retraitAvecFrais() {
        Decor decor = decor("RET");
        produit(decor, "EP-RET", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-RET", "EP-RET", client(decor.entityId(), "T-RET"));
        verser(decor, compte, "100000", "ret-1");

        OperationsService.Receipt recu = retirer(decor, compte, "20000", "ret-2");

        assertThat(recu.amount()).isEqualTo(xof("20000"));
        assertThat(recu.fee()).isEqualTo(xof("500"));
        assertThat(recu.tax()).isEqualTo(xof("90"));               // 18 % de 500
        assertThat(recu.bookingDate()).isEqualTo(J);
        assertThat(recu.valueDate()).isEqualTo(J);
        assertThat(recu.balanceAfter()).isEqualTo(xof("79410"));
        assertThat(recu.replayed()).isFalse();
        assertThat(solde(compte)).isEqualTo(xof("79410"));
        assertThat(solde(decor.caisse())).isEqualTo(xof("80000"));
        assertThat(solde(decor.produitsFrais())).isEqualTo(xof("500"));
        assertThat(solde(decor.taxe())).isEqualTo(xof("90"));
    }

    @Test
    @DisplayName("un retrait deplace est servi par la caisse d'une autre agence : le frais lui revient, le compte reste dans la sienne")
    void retraitDeplace() {
        Decor decor = decor("DPL");
        produit(decor, "EP-DPL", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-DPL", "EP-DPL", client(decor.entityId(), "T-DPL"));
        verser(decor, compte, "100000", "dpl-1");
        var liaisonB = account(decor.entityId(), "DPL-LIAISON-B",
                               io.corebanking.ledger.domain.account.AccountKind.GL,
                               io.corebanking.ledger.domain.account.NormalBalance.DEBIT);
        UUID agenceB = database.inTransaction(c -> io.corebanking.ledger.store.Branches.create(
            c, decor.entityId(), "B", "Agence B", io.corebanking.ledger.store.Branches.Kind.BRANCH,
            null, J, Map.of(io.corebanking.kernel.money.Currencies.XOF, liaisonB.id())));
        var caisseB = new io.corebanking.ledger.domain.account.Account(
            UUID.randomUUID(), decor.entityId(), "DPL-CAISSE-B",
            io.corebanking.ledger.domain.account.AccountKind.INTERNAL,
            io.corebanking.ledger.domain.account.NormalBalance.DEBIT,
            io.corebanking.kernel.money.Currencies.XOF, true, false, 1,
            io.corebanking.ledger.domain.account.AccountStatus.ACTIVE, agenceB);
        database.inTransaction(c -> {
            io.corebanking.ledger.store.Accounts.create(c, caisseB, J);
            return null;
        });

        OperationsService.Receipt recu = operations.withdraw(new OperationsService.Withdrawal(
            IdempotencyKey.of("dpl-2"), decor.entityId(), compte, caisseB.id(), xof("20000"),
            null, "retrait deplace", ACTOR));

        assertThat(recu.remote()).isTrue();
        assertThat(recu.branchId()).isEqualTo(agenceB);
        assertThat(recu.fee()).isEqualTo(xof("500"));
        assertThat(solde(compte)).isEqualTo(xof("79410"));
        assertThat(solde(caisseB)).isEqualTo(xof("-20000"));
        // Le compte du client est au siege : la seule agence a equilibrer est B, en deux lignes.
        assertThat(nombreDeLignes(recu.entryId())).isEqualTo(6);   // 4 lignes metier, 2 de liaison
        assertThat(agenceDesLignes(recu.entryId()))
            .containsEntry(decor.produitsFrais().id(), agenceB)
            .containsEntry(decor.taxe().id(), agenceB)
            .containsEntry(compte, siege(decor));
        assertThat(solde(liaisonB).isZero()).isTrue();

        // A sa propre caisse, rien de deplace.
        OperationsService.Receipt local = retirer(decor, compte, "1000", "dpl-3");
        assertThat(local.remote()).isFalse();
        assertThat(nombreDeLignes(local.entryId())).isEqualTo(4);
    }

    @Test
    @DisplayName("la date de valeur vient des conditions de banque : au guichet, un versement prend valeur le jour ouvre suivant")
    void dateDeValeur() {
        Decor decor = decor("DDV");
        produit(decor, "EP-DDV", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-DDV", "EP-DDV", client(decor.entityId(), "T-DDV"));

        OperationsService.Receipt guichet = verser(decor, compte, "50000", "ddv-1", "GUICHET");
        OperationsService.Receipt autre = verser(decor, compte, "50000", "ddv-2", "AGENCE_MOBILE");

        assertThat(guichet.valueDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(autre.valueDate()).isEqualTo(J);
        // Seule la ligne du client porte la date de valeur des conditions ; la caisse est au jour.
        assertThat(valueDates(guichet.entryId())).containsEntry(compte, LocalDate.of(2026, 9, 16))
            .containsEntry(decor.caisse().id(), J);

        // Sans condition, pas d'operation : le repli sur la date comptable serait plausible et faux.
        Decor sansConditions = decor("DDV2", false);
        produit(sansConditions, "EP-DDV2", "SAVINGS_ACCOUNT", Map.of());
        UUID autreCompte = ouvrir(sansConditions, "CLI-DDV2", "EP-DDV2",
                                  client(sansConditions.entityId(), "T-DDV2"));
        assertThatThrownBy(() -> verser(sansConditions, autreCompte, "1000", "ddv-3"))
            .isInstanceOf(ValueDatePolicy.NoRuleException.class);
        assertThat(solde(autreCompte).isZero()).isTrue();
    }

    @Test
    @DisplayName("un virement debite l'emetteur du montant et des frais, credite le beneficiaire du montant")
    void virement() {
        Decor decor = decor("VIR");
        produit(decor, "EP-VIR", "SAVINGS_ACCOUNT", frais(decor));
        UUID titulaire = client(decor.entityId(), "T-VIR");
        UUID emetteur = ouvrir(decor, "CLI-VIR-1", "EP-VIR", titulaire);
        UUID beneficiaire = ouvrir(decor, "CLI-VIR-2", "EP-VIR", client(decor.entityId(), "T-VIR2"));
        verser(decor, emetteur, "100000", "vir-1");

        OperationsService.Receipt recu = virer(decor, emetteur, beneficiaire, "30000", "vir-2");

        assertThat(recu.fee()).isEqualTo(xof("200"));
        assertThat(recu.tax()).isEqualTo(xof("36"));
        assertThat(solde(emetteur)).isEqualTo(xof("69764"));
        assertThat(solde(beneficiaire)).isEqualTo(xof("30000"));
        assertThat(solde(decor.produitsFrais())).isEqualTo(xof("200"));

        assertThatThrownBy(() -> virer(decor, emetteur, emetteur, "1", "vir-3"))
            .isInstanceOf(IllegalArgumentException.class);
        // Un virement vers un compte d'une autre entite n'est pas un virement interne.
        Decor ailleurs = decor("VIR2");
        produit(ailleurs, "EP-VIR2", "SAVINGS_ACCOUNT", Map.of());
        UUID etranger = ouvrir(ailleurs, "CLI-VIR2", "EP-VIR2", client(ailleurs.entityId(), "T-X"));
        assertThatThrownBy(() -> virer(decor, emetteur, etranger, "1000", "vir-4"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("autre entite");
        assertThat(solde(emetteur)).isEqualTo(xof("69764"));
    }

    @Test
    @DisplayName("une operation rejouee avec la meme cle rend son premier resultat sans rien comptabiliser")
    void rejeu() {
        Decor decor = decor("REJ");
        produit(decor, "EP-REJ", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-REJ", "EP-REJ", client(decor.entityId(), "T-REJ"));
        verser(decor, compte, "100000", "rej-1");

        OperationsService.Receipt premier = retirer(decor, compte, "10000", "rej-2");
        OperationsService.Receipt second = retirer(decor, compte, "10000", "rej-2");

        assertThat(second.replayed()).isTrue();
        assertThat(second.entryId()).isEqualTo(premier.entryId());
        assertThat(second.balanceAfter()).isEqualTo(premier.balanceAfter());
        assertThat(solde(compte)).isEqualTo(xof("89410"));
    }

    @Test
    @DisplayName("le disponible se respecte, et un montant nul ou non comptabilisable est refuse avant tout")
    void refus() {
        Decor decor = decor("REF");
        produit(decor, "EP-REF", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-REF", "EP-REF", client(decor.entityId(), "T-REF"));
        verser(decor, compte, "1000", "ref-1");

        assertThatThrownBy(() -> retirer(decor, compte, "1001", "ref-2"))
            .isInstanceOf(InsufficientFundsException.class);
        assertThatThrownBy(() -> retirer(decor, compte, "0", "ref-3"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retirer(decor, compte, "10.5", "ref-4"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("comptabilisable");
        assertThat(solde(compte)).isEqualTo(xof("1000"));

        // La caisse doit etre un compte interne de l'entite : un compte client ne fait pas caisse.
        UUID autre = ouvrir(decor, "CLI-REF-2", "EP-REF", client(decor.entityId(), "T-REF2"));
        assertThatThrownBy(() -> operations.deposit(new OperationsService.Deposit(
                IdempotencyKey.of("ref-5"), decor.entityId(), compte, autre, xof("100"), null, null,
                ACTOR)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("caisse");
    }

    @Test
    @DisplayName("un tiers bloque ne peut plus operer sur ses comptes, jusqu'a sa levee")
    void tiersBloque() {
        Decor decor = decor("TBQ");
        produit(decor, "EP-TBQ", "SAVINGS_ACCOUNT", Map.of());
        UUID titulaire = client(decor.entityId(), "T-TBQ");
        UUID compte = ouvrir(decor, "CLI-TBQ", "EP-TBQ", titulaire);
        verser(decor, compte, "1000", "tbq-1");

        parties.block(titulaire, "alerte LCB-FT", J, ACTOR, APPROVER);
        assertThatThrownBy(() -> verser(decor, compte, "1", "tbq-2"))
            .isInstanceOf(PartyService.PartyNotOperableException.class);

        parties.unblock(titulaire, "alerte levee", J, ACTOR, APPROVER);
        verser(decor, compte, "1", "tbq-3");
        assertThat(solde(compte)).isEqualTo(xof("1001"));
    }

    // ------------------------------------------------------------------ outillage

    /** Agence comptable de chaque ligne de l'ecriture, par compte (premiere ligne du compte). */
    private static Map<UUID, UUID> agenceDesLignes(UUID entryId) {
        return database.inTransaction(c -> {
            Map<UUID, UUID> agences = new LinkedHashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, branch_id FROM journal_line WHERE entry_id = ?"
                + " AND kind = 'BUSINESS' ORDER BY line_number")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        agences.putIfAbsent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
                    }
                }
            } catch (SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
            return agences;
        });
    }

    private static long nombreDeLignes(UUID entryId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT count(*) FROM journal_line WHERE entry_id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
        });
    }

    private static Map<UUID, LocalDate> valueDates(UUID entryId) {
        return database.inTransaction(c -> {
            Map<UUID, LocalDate> dates = new LinkedHashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, value_date FROM journal_line WHERE entry_id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        dates.put(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class));
                    }
                }
            } catch (SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
            return dates;
        });
    }
}
