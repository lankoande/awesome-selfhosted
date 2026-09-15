package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.product.ProductCatalog;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La dormance se constate sur l'absence d'operation du client, et se defait a la premiere. */
class DormancyIT extends DepositsTestBase {

    /** Un compte ouvert il y a quatorze mois, rattache au produit, avec son titulaire. */
    private static UUID ancienCompte(Decor decor, String code, String produit, UUID titulaire) {
        Account account = new Account(UUID.randomUUID(), decor.entityId(), code,
                                      AccountKind.CUSTOMER, NormalBalance.CREDIT, Currencies.XOF,
                                      true, true, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, J.minusMonths(14));
            ProductCatalog.assignProduct(c, account.id(), produit, J.minusMonths(14), null);
            AccountHolders.attach(c, account.id(), titulaire, HolderRole.HOLDER, J.minusMonths(14),
                                  ACTOR);
            return null;
        });
        return account.id();
    }

    private static void ecriture(Decor decor, UUID accountId, LocalDate bookingDate, String key,
                                 boolean batch) {
        List<PostingLine> lines = List.of(
            PostingLine.debit(decor.caisse().id(), xof("1000"), bookingDate, null),
            PostingLine.credit(accountId, xof("1000"), bookingDate, null));
        postingService.post(batch
            ? PostingCommand.batch(IdempotencyKey.of(key), decor.entityId(), bookingDate,
                                   "FEE_REVERSAL", ACTOR, UUID.randomUUID(), lines)
            : PostingCommand.online(IdempotencyKey.of(key), decor.entityId(), bookingDate,
                                    "CASH_DEPOSIT", ACTOR, lines));
    }

    @Test
    @DisplayName("douze mois sans operation du client : dormant ; une ecriture de la banque ne compte pas")
    void detection() {
        Decor decor = decor("DOR");
        produit(decor, "EP-DOR", "SAVINGS_ACCOUNT", Map.of(DepositCatalog.P_DORMANCY_MONTHS, "12"));
        produit(decor, "EP-SANS", "SAVINGS_ACCOUNT", Map.of());
        UUID titulaire = client(decor.entityId(), "T-DOR");
        UUID oublie = ancienCompte(decor, "CLI-DOR-1", "EP-DOR", titulaire);
        UUID vivant = ancienCompte(decor, "CLI-DOR-2", "EP-DOR", titulaire);
        UUID sansDormance = ancienCompte(decor, "CLI-DOR-3", "EP-SANS", titulaire);
        UUID recent = ouvrir(decor, "CLI-DOR-4", "EP-DOR", titulaire);
        ecriture(decor, oublie, J.minusMonths(13), "dor-1", false);
        ecriture(decor, oublie, J.minusMonths(2), "dor-2", true);        // la banque, pas le client
        ecriture(decor, vivant, J.minusMonths(2), "dor-3", false);

        UUID run = UUID.randomUUID();
        List<UUID> dormants = database.inTransaction(
            c -> Dormancy.detect(c, decor.entityId(), J, run, ACTOR));

        assertThat(dormants).containsExactly(oublie);
        assertThat(compte(oublie).status()).isEqualTo(AccountStatus.DORMANT);
        assertThat(compte(vivant).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(compte(sansDormance).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(compte(recent).status()).isEqualTo(AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            var events = AccountLifecycle.history(c, oublie);
            assertThat(events).extracting(AccountLifecycle.Event::kind).containsExactly("DORMANT");
            assertThat(events.get(0).batchRunId()).isEqualTo(run);
            // Une seconde passe ne le constate pas deux fois.
            assertThat(Dormancy.detect(c, decor.entityId(), J, UUID.randomUUID(), ACTOR)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("un compte dormant continue de recevoir, et se reveille a l'operation de son client")
    void reveil() {
        Decor decor = decor("REV");
        produit(decor, "EP-REV", "SAVINGS_ACCOUNT", Map.of(DepositCatalog.P_DORMANCY_MONTHS, "6"));
        UUID compte = ancienCompte(decor, "CLI-REV", "EP-REV", client(decor.entityId(), "T-REV"));
        database.inTransaction(c -> Dormancy.detect(c, decor.entityId(), J, UUID.randomUUID(),
                                                    ACTOR));
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.DORMANT);

        verser(decor, compte, "2500", "rev-1");

        assertThat(compte(compte).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(solde(compte)).isEqualTo(xof("2500"));
        assertThat(historique(compte)).containsExactly("DORMANT", "REACTIVATED");
    }

    @Test
    @DisplayName("l'annulation de l'arrete defait les mises en dormance qu'il a prononcees")
    void annulation() {
        Decor decor = decor("ANN");
        produit(decor, "EP-ANN", "SAVINGS_ACCOUNT", Map.of(DepositCatalog.P_DORMANCY_MONTHS, "6"));
        UUID compte = ancienCompte(decor, "CLI-ANN", "EP-ANN", client(decor.entityId(), "T-ANN"));
        UUID run = UUID.randomUUID();
        database.inTransaction(c -> Dormancy.detect(c, decor.entityId(), J, run, ACTOR));
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.DORMANT);

        int restaures = database.inTransaction(c -> Dormancy.cancelRun(c, run));

        assertThat(restaures).isEqualTo(1);
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(historique(compte)).isEmpty();
    }
}
