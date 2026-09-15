package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Une caisse par guichetier ; sa journee se termine par un arrete, ecart compris. */
class TillsIT extends DepositsTestBase {

    private static TillService tills() {
        return new TillService(database, postingService);
    }

    private static List<String> nonArretees(Decor decor) {
        return database.inTransaction(c -> Tills.movedAndUnclosed(c, decor.entityId(), J));
    }

    private static UUID caisse(Decor decor, String code, Account compte, String guichetier,
                               Account ecarts) {
        return database.inTransaction(c -> Tills.create(c, new Tills.Draft(
            decor.entityId(), code, compte.id(), guichetier,
            ecarts == null ? null : ecarts.id(), ACTOR, APPROVER)));
    }

    @Test
    @DisplayName("une caisse se cree a deux, sur un compte interne d'agence, une par guichetier")
    void creation() {
        Decor decor = decor("TIL-C");
        UUID id = caisse(decor, "C-01", decor.caisse(), "guichetier-1", decor.attente());

        Tills.Till till = database.inTransaction(c -> Tills.require(c, id));
        assertThat(till.branchId()).isEqualTo(siege(decor));
        assertThat(till.cashAccountId()).isEqualTo(decor.caisse().id());
        Optional<Tills.Till> duGuichetier = database.inTransaction(
            c -> Tills.forTeller(c, decor.entityId(), "guichetier-1"));
        assertThat(duGuichetier).map(Tills.Till::id).contains(id);
        List<Tills.Till> caisses = database.inTransaction(c -> Tills.ofEntity(c, decor.entityId()));
        assertThat(caisses).extracting(Tills.Till::code).containsExactly("C-01");

        Account autre = account(decor.entityId(), "TIL-C-CAISSE-2", AccountKind.INTERNAL,
                                NormalBalance.DEBIT);
        // Le meme guichetier ne tient pas deux caisses.
        assertThatThrownBy(() -> caisse(decor, "C-02", autre, "guichetier-1", null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("tient deja");
        // Un compte general n'est pas une caisse.
        assertThatThrownBy(() -> caisse(decor, "C-03", decor.produitsFrais(), "guichetier-2", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compte interne");
        // Un compte de caisse n'appartient qu'a une caisse.
        assertThatThrownBy(() -> caisse(decor, "C-04", decor.caisse(), "guichetier-3", null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deja la caisse");
        // Le demandeur ne valide pas.
        assertThatThrownBy(() -> database.inTransaction(c -> Tills.create(c, new Tills.Draft(
                decor.entityId(), "C-05", autre.id(), "guichetier-4", null, ACTOR, ACTOR))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
    }

    @Test
    @DisplayName("l'arrete sans ecart clot la journee de caisse : plus d'operation, pas de second arrete")
    void closureWithoutDifference() {
        Decor decor = decor("TIL-A");
        produit(decor, "EP-TIL-A", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "TIL-A-CLI", "EP-TIL-A", client(decor.entityId(), "T-TIL-A"));
        UUID id = caisse(decor, "A-01", decor.caisse(), "guichetier-a", decor.attente());

        operations.deposit(new OperationsService.Deposit(
            IdempotencyKey.of("til-a-1"), decor.entityId(), compte, decor.caisse().id(),
            xof("100000"), null, null, ACTOR));
        assertThat(nonArretees(decor)).containsExactly("A-01");

        TillService.Closure arrete = tills().close(new TillService.Closing(id, xof("100000"), ACTOR));

        assertThat(arrete.book()).isEqualTo(xof("100000"));
        assertThat(arrete.difference().isZero()).isTrue();
        assertThat(arrete.entryId()).isNull();
        assertThat(arrete.businessDate()).isEqualTo(J);
        assertThat(nonArretees(decor)).isEmpty();
        boolean arretee = database.inTransaction(c -> Tills.closedOn(c, id, J));
        assertThat(arretee).isTrue();
        // Une journee de caisse ne s'arrete qu'une fois, et une caisse arretee ne sert plus.
        assertThatThrownBy(() -> tills().close(new TillService.Closing(id, xof("100000"), ACTOR)))
            .isInstanceOf(Tills.TillClosedException.class);
        assertThatThrownBy(() -> operations.deposit(new OperationsService.Deposit(
                IdempotencyKey.of("til-a-2"), decor.entityId(), compte, decor.caisse().id(),
                xof("1000"), null, null, ACTOR)))
            .isInstanceOf(Tills.TillClosedException.class).hasMessageContaining("A-01");
    }

    @Test
    @DisplayName("un ecart a l'arrete est comptabilise sur le compte d'ecart, jamais ajuste ; sans compte d'ecart, il est refuse")
    void closureWithDifference() {
        Decor decor = decor("TIL-E");
        produit(decor, "EP-TIL-E", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "TIL-E-CLI", "EP-TIL-E", client(decor.entityId(), "T-TIL-E"));
        UUID id = caisse(decor, "E-01", decor.caisse(), "guichetier-e", decor.attente());
        operations.deposit(new OperationsService.Deposit(
            IdempotencyKey.of("til-e-1"), decor.entityId(), compte, decor.caisse().id(),
            xof("100000"), null, null, ACTOR));

        // Il manque mille francs : le manquant va au compte d'ecart, la caisse retombe au comptage.
        TillService.Closure manquant = tills().close(new TillService.Closing(id, xof("99000"), ACTOR));

        assertThat(manquant.difference()).isEqualTo(xof("-1000"));
        assertThat(manquant.entryId()).isNotNull();
        database.inTransaction(c -> {
            assertThat(Balances.current(c, decor.caisse().id())).isEqualTo(xof("99000"));
            assertThat(Balances.current(c, decor.attente().id()).abs()).isEqualTo(xof("1000"));
            return null;
        });
        assertThat(nonArretees(decor)).isEmpty();

        // Une caisse sans compte d'ecart ne peut pas constater d'ecart : l'arrete est refuse,
        // et la caisse reste a arreter.
        Account caisse2 = account(decor.entityId(), "TIL-E-CAISSE-2", AccountKind.INTERNAL,
                                  NormalBalance.DEBIT);
        UUID id2 = caisse(decor, "E-02", caisse2, "guichetier-e2", null);
        operations.deposit(new OperationsService.Deposit(
            IdempotencyKey.of("til-e-2"), decor.entityId(), compte, caisse2.id(), xof("50000"),
            null, null, ACTOR));
        assertThatThrownBy(() -> tills().close(new TillService.Closing(id2, xof("49000"), ACTOR)))
            .isInstanceOf(TillService.UnjustifiedDifferenceException.class);
        assertThat(nonArretees(decor)).containsExactly("E-02");
        // Un comptage juste, lui, passe.
        assertThat(tills().close(new TillService.Closing(id2, xof("50000"), ACTOR)).entryId())
            .isNull();
    }
}
