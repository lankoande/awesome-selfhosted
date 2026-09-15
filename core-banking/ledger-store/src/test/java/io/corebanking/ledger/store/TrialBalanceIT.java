package io.corebanking.ledger.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La balance et le grand livre : lus dans le journal, a six colonnes, et par curseur. */
class TrialBalanceIT extends LedgerTestBase {

    private static final LocalDate AOUT_20 = LocalDate.of(2026, 8, 20);
    private static final LocalDate SEPT_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEPT_5 = LocalDate.of(2026, 9, 5);
    private static final LocalDate SEPT_10 = LocalDate.of(2026, 9, 10);

    private static Account caisse;
    private static Account clientA;
    private static Account clientB;
    private static Account produits;

    @BeforeAll
    static void mouvements() {
        caisse = newGlAccount("TB-CAISSE", XOF, NormalBalance.DEBIT, 1);
        clientA = newCustomerAccount("TB-CLI-A", XOF);
        clientB = newCustomerAccount("TB-CLI-B", XOF);
        produits = new Account(UUID.randomUUID(), ENTITY, "TB-PRODUITS", AccountKind.GL,
                               NormalBalance.CREDIT, XOF, true, false, 1, AccountStatus.ACTIVE)
            .withNature(AccountNature.PROFIT_AND_LOSS);
        database.inTransaction(c -> { Accounts.create(c, produits); return null; });

        post("tb-1", AOUT_20, "DEPOSIT", caisse, clientA, "10000");
        post("tb-2", SEPT_5, "DEPOSIT", caisse, clientB, "5000");
        post("tb-3", SEPT_10, "FEE", clientA, produits, "500");
    }

    private static void post(String key, LocalDate date, String type, Account debit,
                             Account credit, String amount) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, type, ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of(amount, XOF), date, null),
                    PostingLine.credit(credit.id(), Money.of(amount, XOF), date, null))));
    }

    private static TrialBalance.Line ligne(List<TrialBalance.Line> lines, Account account) {
        return lines.stream().filter(l -> l.accountId().equals(account.id())).findFirst()
            .orElseThrow();
    }

    private static Money xof(String amount) {
        return Money.of(amount, XOF);
    }

    @Test
    @DisplayName("la balance de septembre : ouverture, mouvements et cloture par compte, totaux equilibres colonne a colonne")
    void the_balance_has_six_columns_and_balances() {
        List<TrialBalance.Line> lines = database.inTransaction(c ->
            TrialBalance.page(c, ENTITY, SEPT_1, BUSINESS_DATE, TrialBalance.Filter.NONE, 0, 100));
        long count = database.inTransaction(c ->
            TrialBalance.count(c, ENTITY, SEPT_1, BUSINESS_DATE, TrialBalance.Filter.NONE));
        assertThat(count).isEqualTo(4);
        assertThat(lines).extracting(TrialBalance.Line::code)
            .containsExactly("TB-CAISSE", "TB-CLI-A", "TB-CLI-B", "TB-PRODUITS");

        TrialBalance.Line caisseL = ligne(lines, caisse);
        assertThat(caisseL.openingDebit()).isEqualTo(xof("10000"));
        assertThat(caisseL.openingCredit().isZero()).isTrue();
        assertThat(caisseL.movementDebit()).isEqualTo(xof("5000"));
        assertThat(caisseL.movementCredit().isZero()).isTrue();
        assertThat(caisseL.closingDebit()).isEqualTo(xof("15000"));
        assertThat(caisseL.kind()).isEqualTo(AccountKind.GL);

        TrialBalance.Line a = ligne(lines, clientA);
        assertThat(a.openingCredit()).isEqualTo(xof("10000"));
        assertThat(a.movementDebit()).isEqualTo(xof("500"));
        assertThat(a.closingCredit()).isEqualTo(xof("9500"));
        assertThat(a.closingDebit().isZero()).isTrue();

        TrialBalance.Line b = ligne(lines, clientB);
        assertThat(b.openingDebit().isZero()).isTrue();
        assertThat(b.openingCredit().isZero()).isTrue();
        assertThat(b.movementCredit()).isEqualTo(xof("5000"));
        assertThat(b.closingCredit()).isEqualTo(xof("5000"));

        TrialBalance.Line p = ligne(lines, produits);
        assertThat(p.nature()).isEqualTo(AccountNature.PROFIT_AND_LOSS);
        assertThat(p.closingCredit()).isEqualTo(xof("500"));

        List<TrialBalance.Totals> totals = database.inTransaction(c ->
            TrialBalance.totals(c, ENTITY, SEPT_1, BUSINESS_DATE, TrialBalance.Filter.NONE));
        assertThat(totals).hasSize(1);
        TrialBalance.Totals xof = totals.get(0);
        assertThat(xof.accounts()).isEqualTo(4);
        assertThat(xof.openingDebit()).isEqualTo(xof("10000"));
        assertThat(xof.openingCredit()).isEqualTo(xof("10000"));
        assertThat(xof.movementDebit()).isEqualTo(xof("5500"));
        assertThat(xof.movementCredit()).isEqualTo(xof("5500"));
        assertThat(xof.closingDebit()).isEqualTo(xof("15000"));
        assertThat(xof.closingCredit()).isEqualTo(xof("15000"));
        assertThat(xof.balanced()).isTrue();

        // La pagination decoupe la meme liste, dans le meme ordre.
        List<TrialBalance.Line> page2 = database.inTransaction(c ->
            TrialBalance.page(c, ENTITY, SEPT_1, BUSINESS_DATE, TrialBalance.Filter.NONE, 2, 2));
        assertThat(page2).extracting(TrialBalance.Line::code)
            .containsExactly("TB-CLI-B", "TB-PRODUITS");
    }

    @Test
    @DisplayName("la balance auxiliaire des clients et la balance d'agence sont des filtres de la meme lecture")
    void customer_and_branch_balances_are_filters() {
        var clients = new TrialBalance.Filter(AccountKind.CUSTOMER, null);
        List<TrialBalance.Line> lines = database.inTransaction(c ->
            TrialBalance.page(c, ENTITY, SEPT_1, BUSINESS_DATE, clients, 0, 100));
        assertThat(lines).extracting(TrialBalance.Line::code)
            .containsExactly("TB-CLI-A", "TB-CLI-B");
        long nombreClients = database.inTransaction(c ->
            TrialBalance.count(c, ENTITY, SEPT_1, BUSINESS_DATE, clients));
        assertThat(nombreClients).isEqualTo(2);
        // Filtree, la balance ne s'equilibre plus : elle ne le pretend pas.
        TrialBalance.Totals totals = database.inTransaction(c ->
            TrialBalance.totals(c, ENTITY, SEPT_1, BUSINESS_DATE, clients)).get(0);
        assertThat(totals.accounts()).isEqualTo(2);
        assertThat(totals.closingCredit()).isEqualTo(xof("14500"));
        assertThat(totals.balanced()).isFalse();

        UUID siege = database.inTransaction(c -> Branches.headOffice(c, ENTITY));
        var agence = new TrialBalance.Filter(null, siege);
        long auSiege = database.inTransaction(c ->
            TrialBalance.count(c, ENTITY, SEPT_1, BUSINESS_DATE, agence));
        assertThat(auSiege).isEqualTo(4);
        var inconnue = new TrialBalance.Filter(null, UUID.randomUUID());
        List<TrialBalance.Line> nullePart = database.inTransaction(c ->
            TrialBalance.page(c, ENTITY, SEPT_1, BUSINESS_DATE, inconnue, 0, 100));
        assertThat(nullePart).isEmpty();

        assertThatThrownBy(() -> database.inTransaction(c ->
            TrialBalance.page(c, ENTITY, BUSINESS_DATE, SEPT_1, TrialBalance.Filter.NONE, 0, 10)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inversee");
    }

    @Test
    @DisplayName("le grand livre par curseur : chaque page reprend apres la precedente, sans doublon ni omission, dans l'ordre du releve")
    void the_ledger_is_read_by_cursor_in_the_statement_order() {
        // Le releve par pages numerotees, la reference.
        List<Journal.StatementLine> releve = database.inTransaction(c ->
            Journal.statement(c, caisse.id(), AOUT_20, BUSINESS_DATE, 0, 100));
        assertThat(releve).hasSize(2);
        assertThat(releve.get(0).bookingDate()).isEqualTo(AOUT_20);
        assertThat(releve.get(0).accountCode()).isEqualTo("TB-CAISSE");
        assertThat(releve.get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(releve.get(0).transactionType()).isEqualTo("DEPOSIT");
        assertThat(releve.get(1).bookingDate()).isEqualTo(SEPT_5);
        long lignesCaisse = database.inTransaction(c ->
            Journal.countStatement(c, caisse.id(), AOUT_20, BUSINESS_DATE));
        assertThat(lignesCaisse).isEqualTo(2);

        // Le meme releve, une ligne par page, par curseur.
        List<Journal.StatementLine> parCurseur = new ArrayList<>();
        Journal.Position position = null;
        while (true) {
            Journal.Position after = position;
            List<Journal.StatementLine> page = database.inTransaction(c ->
                Journal.statementAfter(c, caisse.id(), AOUT_20, BUSINESS_DATE, after, 1));
            if (page.isEmpty()) {
                break;
            }
            assertThat(page).hasSize(1);
            parCurseur.addAll(page);
            position = page.get(page.size() - 1).position();
        }
        assertThat(parCurseur).containsExactlyElementsOf(releve);

        // Le journal de l'entite, toutes lignes, par pages de deux : six lignes, trois ecritures,
        // chaque page apres la precedente.
        List<Journal.StatementLine> journal = new ArrayList<>();
        position = null;
        int pages = 0;
        while (true) {
            Journal.Position after = position;
            List<Journal.StatementLine> page = database.inTransaction(c ->
                Journal.journalAfter(c, ENTITY, AOUT_20, BUSINESS_DATE, after, 2));
            if (page.isEmpty()) {
                break;
            }
            pages++;
            journal.addAll(page);
            position = page.get(page.size() - 1).position();
        }
        assertThat(pages).isEqualTo(3);
        assertThat(journal).hasSize(6);
        assertThat(journal.stream().map(l -> l.entryId() + "/" + l.lineNumber()).distinct())
            .hasSize(6);
        assertThat(journal).extracting(Journal.StatementLine::bookingDate)
            .containsExactly(AOUT_20, AOUT_20, SEPT_5, SEPT_5, SEPT_10, SEPT_10);
        assertThat(journal.get(4).accountCode()).isEqualTo("TB-CLI-A");
        assertThat(journal.get(5).accountCode()).isEqualTo("TB-PRODUITS");
        // Une plage vide est une page vide, pas une erreur.
        List<Journal.StatementLine> vide = database.inTransaction(c ->
            Journal.journalAfter(c, ENTITY, SEPT_1, SEPT_1, null, 10));
        assertThat(vide).isEmpty();
    }

    @Test
    @DisplayName("la reprise apres une position est une condition d'index, pour le compte comme pour l'entite : le cout d'une page ne depend pas de ce qui la precede")
    void the_cursor_is_served_by_an_index_condition() {
        Journal.Position position = database.inTransaction(c ->
            Journal.journalAfter(c, ENTITY, AOUT_20, BUSINESS_DATE, null, 1)).get(0).position();
        // Sur une table de six lignes, le planificateur prefere tout lire : on lui retire ce
        // choix pour voir si l'index peut porter la reprise — c'est ce qui compte en production.
        String parCompte = plan(
            "SELECT l.id FROM journal_line l WHERE l.account_id = '" + caisse.id() + "'"
            + " AND l.booking_date >= '" + AOUT_20 + "' AND l.booking_date <= '" + BUSINESS_DATE
            + "' AND (l.booking_date, l.knowledge_time, l.entry_id, l.line_number) > ("
            + literal(position) + ")"
            + " ORDER BY l.booking_date, l.knowledge_time, l.entry_id, l.line_number LIMIT 10");
        String parEntite = plan(
            "SELECT l.id FROM journal_line l WHERE l.legal_entity_id = '" + ENTITY + "'"
            + " AND l.booking_date >= '" + AOUT_20 + "' AND l.booking_date <= '" + BUSINESS_DATE
            + "' AND (l.booking_date, l.knowledge_time, l.entry_id, l.line_number) > ("
            + literal(position) + ")"
            + " ORDER BY l.booking_date, l.knowledge_time, l.entry_id, l.line_number LIMIT 10");
        for (String plan : List.of(parCompte, parEntite)) {
            assertThat(plan).as(plan).contains("Index");
            assertThat(plan).as(plan)
                .contains("Index Cond")
                .contains("ROW(booking_date, knowledge_time, entry_id, line_number) > ROW(");
            assertThat(plan).as(plan).doesNotContain("Seq Scan");
        }
    }

    private static String literal(Journal.Position position) {
        return "'" + position.bookingDate() + "'::date, '"
            + java.time.OffsetDateTime.ofInstant(position.knowledgeTime(), java.time.ZoneOffset.UTC)
            + "'::timestamptz, '" + position.entryId() + "'::uuid, " + position.lineNumber();
    }

    private static String plan(String sql) {
        return database.inTransaction(c -> {
            StringBuilder plan = new StringBuilder();
            try (var ps = c.prepareStatement("SET LOCAL enable_seqscan = off")) {
                ps.execute();
            } catch (java.sql.SQLException e) {
                throw new LedgerStoreException("Reglage du planificateur", e);
            }
            try (var ps = c.prepareStatement("EXPLAIN " + sql); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            } catch (java.sql.SQLException e) {
                throw new LedgerStoreException("Plan d'execution", e);
            }
            return plan.toString();
        });
    }
}
