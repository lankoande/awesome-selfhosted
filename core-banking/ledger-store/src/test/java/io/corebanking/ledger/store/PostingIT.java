package io.corebanking.ledger.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostingIT extends LedgerTestBase {

    private PostingResult deposit(Account client, Account caisse, String amount, String key) {
        return postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, BUSINESS_DATE, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), BUSINESS_DATE, "Versement"),
                    PostingLine.credit(client.id(), Money.of(amount, XOF), BUSINESS_DATE, "Versement"))));
    }

    @Test
    @DisplayName("un versement credite le client et debite la caisse, et la balance reste equilibree")
    void deposit_updates_both_sides() {
        Account client = newCustomerAccount("CLI-100", XOF);
        Account caisse = newGlAccount("GL-CAISSE-100", XOF, NormalBalance.DEBIT, 1);

        PostingResult result = deposit(client, caisse, "250000", "dep-100");

        assertThat(result.replayed()).isFalse();
        assertThat(result.balancesAfter().get(client.id())).isEqualTo(Money.of("250000", XOF));
        assertThat(result.balancesAfter().get(caisse.id())).isEqualTo(Money.of("250000", XOF));

        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("rejouer la meme cle d'idempotence ne produit pas de seconde ecriture")
    void replay_is_idempotent() {
        Account client = newCustomerAccount("CLI-101", XOF);
        Account caisse = newGlAccount("GL-CAISSE-101", XOF, NormalBalance.DEBIT, 1);

        PostingResult first = deposit(client, caisse, "100000", "dep-101");
        PostingResult second = deposit(client, caisse, "100000", "dep-101");

        assertThat(second.replayed()).isTrue();
        assertThat(second.entryId()).isEqualTo(first.entryId());

        database.inTransaction(c -> {
            assertThat(Journal.countEntriesWithIdempotencyKey(c, "dep-101")).isEqualTo(1);
            assertThat(Balances.current(c, client.id())).isEqualTo(Money.of("100000", XOF));
            return null;
        });
    }

    @Test
    @DisplayName("le journal refuse toute modification, meme en SQL direct")
    void journal_is_immutable() {
        Account client = newCustomerAccount("CLI-102", XOF);
        Account caisse = newGlAccount("GL-CAISSE-102", XOF, NormalBalance.DEBIT, 1);
        deposit(client, caisse, "50000", "dep-102");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("UPDATE journal_line SET amount = 1 WHERE amount = 50000");
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("refus attendu", e);
            }
        // Le declencheur est herite par les partitions : il se declenche sur la table feuille
        // (journal_line_2026_09), ce qui confirme qu'aucune partition n'echappe a l'immuabilite.
        })).rootCause()
            .hasMessageContaining("Le journal comptable est immuable")
            .hasMessageContaining("UPDATE interdit sur journal_line_")
            .hasMessageContaining("Corriger par contre-passation");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("DELETE FROM journal_entry WHERE transaction_type = 'DEPOSIT'");
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("refus attendu", e);
            }
        })).hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("une contre-passation ramene le solde a l'identique et ne joue qu'une fois")
    void reversal_restores_balance_and_is_unique() {
        Account client = newCustomerAccount("CLI-103", XOF);
        Account caisse = newGlAccount("GL-CAISSE-103", XOF, NormalBalance.DEBIT, 1);

        PostingResult original = deposit(client, caisse, "75000", "dep-103");
        assertThat(original.balancesAfter().get(client.id())).isEqualTo(Money.of("75000", XOF));

        postingService.reverse(original.entryId(), BUSINESS_DATE, BUSINESS_DATE,
                               IdempotencyKey.of("rev-103"), "Erreur de guichet");

        database.inTransaction(c -> {
            assertThat(Balances.current(c, client.id())).isEqualTo(Money.zero(XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });

        assertThatThrownBy(() -> postingService.reverse(
            original.entryId(), BUSINESS_DATE, BUSINESS_DATE,
            IdempotencyKey.of("rev-103-bis"), "Seconde tentative"))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("deja ete contre-passee");
    }

    @Test
    @DisplayName("aucune ecriture n'est acceptee dans une periode fermee")
    void closed_period_is_refused() {
        Account client = newCustomerAccount("CLI-104", XOF);
        Account caisse = newGlAccount("GL-CAISSE-104", XOF, NormalBalance.DEBIT, 1);
        LocalDate horsPeriode = BUSINESS_DATE.minusMonths(4);

        assertThatThrownBy(() -> postingService.post(PostingCommand.online(
            IdempotencyKey.of("dep-104"), ENTITY, horsPeriode, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of("1000", XOF), horsPeriode, null),
                    PostingLine.credit(client.id(), Money.of("1000", XOF), horsPeriode, null)))))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("Aucune periode comptable ouverte");
    }

    @Test
    @DisplayName("le disponible est controle : un retrait au-dela du solde est refuse")
    void available_balance_is_enforced() {
        Account client = newCustomerAccount("CLI-105", XOF);
        Account caisse = newGlAccount("GL-CAISSE-105", XOF, NormalBalance.DEBIT, 1);
        deposit(client, caisse, "30000", "dep-105");

        assertThatThrownBy(() -> postingService.post(PostingCommand.online(
            IdempotencyKey.of("wdr-105"), ENTITY, BUSINESS_DATE, "WITHDRAWAL", ACTOR,
            List.of(PostingLine.debit(client.id(), Money.of("50000", XOF), BUSINESS_DATE, "Retrait"),
                    PostingLine.credit(caisse.id(), Money.of("50000", XOF), BUSINESS_DATE, "Retrait")))))
            .isInstanceOf(InsufficientFundsException.class);

        database.inTransaction(c -> {
            assertThat(Balances.current(c, client.id())).isEqualTo(Money.of("30000", XOF));
            return null;
        });
    }

    @Test
    @DisplayName("une ecriture desequilibree est refusee par la base, meme si le domaine est contourne")
    void database_refuses_unbalanced_entry() {
        final String SIEGE = "(SELECT id FROM branch WHERE legal_entity_id = '" + ENTITY
                             + "' AND kind = 'HEAD_OFFICE')";
        Account client = newCustomerAccount("CLI-106", XOF);
        Account caisse = newGlAccount("GL-CAISSE-106", XOF, NormalBalance.DEBIT, 1);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            UUID entryId = UUID.randomUUID();
            try (var st = c.createStatement()) {
                st.executeUpdate(
                    "INSERT INTO journal_entry(id, booking_date, legal_entity_id, entry_number,"
                    + " transaction_type, source, idempotency_key, created_by) VALUES ('"
                    + entryId + "','" + BUSINESS_DATE + "','" + ENTITY
                    + "', nextval('journal_entry_number_seq'),'HACK','ONLINE','hack-106','" + ACTOR + "')");
                st.executeUpdate(
                    "INSERT INTO journal_line(id, booking_date, entry_id, legal_entity_id, line_number,"
                    + " account_id, direction, amount, currency, functional_amount, value_date,"
                    + " branch_id)"
                    + " VALUES ('" + UUID.randomUUID() + "','" + BUSINESS_DATE + "','" + entryId
                    + "','" + ENTITY + "',1,'" + caisse.id() + "','DEBIT',1000,'XOF',1000,'"
                    + BUSINESS_DATE + "'," + SIEGE + ")");
                st.executeUpdate(
                    "INSERT INTO journal_line(id, booking_date, entry_id, legal_entity_id, line_number,"
                    + " account_id, direction, amount, currency, functional_amount, value_date,"
                    + " branch_id)"
                    + " VALUES ('" + UUID.randomUUID() + "','" + BUSINESS_DATE + "','" + entryId
                    + "','" + ENTITY + "',2,'" + client.id() + "','CREDIT',900,'XOF',900,'"
                    + BUSINESS_DATE + "'," + SIEGE + ")");
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("refus attendu", e);
            }
        })).hasStackTraceContaining("desequilibree");
    }
}
