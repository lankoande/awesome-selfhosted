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
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.StatementLayouts.Kind;
import io.corebanking.ledger.store.StatementLayouts.Line;
import io.corebanking.ledger.store.StatementLayouts.LineKind;
import io.corebanking.ledger.store.StatementLayouts.Rule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Les etats financiers : une maquette a deux, appliquee au journal, qui dit ce qu'elle ne sait pas presenter. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementsIT extends LedgerTestBase {

    private static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate DEBUT_EXERCICE = LocalDate.of(2026, 4, 1);
    private static final LocalDate FIN_EXERCICE = LocalDate.of(2027, 3, 31);
    private static final LocalDate D = LocalDate.of(2026, 9, 10);

    private static Account caisse;
    private static Account clientA;
    private static Account clientB;
    private static Account capital;
    private static Account resultat;
    private static Account produits;
    private static Account charges;
    private static Account engagement;
    private static Account contrepartie;
    private static Account divers;
    private static UUID bilan;

    @BeforeAll
    static void decor() {
        caisse = compte("1-CAISSE", AccountKind.GL, NormalBalance.DEBIT, AccountNature.BALANCE_SHEET);
        clientA = compte("2-CLI-A", AccountKind.CUSTOMER, NormalBalance.CREDIT,
                         AccountNature.BALANCE_SHEET);
        clientB = compte("2-CLI-B", AccountKind.CUSTOMER, NormalBalance.CREDIT,
                         AccountNature.BALANCE_SHEET);
        capital = compte("5-CAPITAL", AccountKind.GL, NormalBalance.CREDIT,
                         AccountNature.BALANCE_SHEET);
        resultat = compte("5-RESULTAT", AccountKind.GL, NormalBalance.CREDIT,
                          AccountNature.BALANCE_SHEET);
        produits = compte("7-COM", AccountKind.GL, NormalBalance.CREDIT,
                          AccountNature.PROFIT_AND_LOSS);
        charges = compte("6-FRAIS", AccountKind.GL, NormalBalance.DEBIT,
                         AccountNature.PROFIT_AND_LOSS);
        engagement = compte("9-ENG", AccountKind.GL, NormalBalance.DEBIT,
                            AccountNature.OFF_BALANCE_SHEET);
        contrepartie = compte("9-CTR", AccountKind.GL, NormalBalance.CREDIT,
                              AccountNature.OFF_BALANCE_SHEET);
        divers = compte("3-DIVERS", AccountKind.GL, NormalBalance.DEBIT,
                        AccountNature.BALANCE_SHEET);
        database.inTransaction(c -> {
            Entities.openPeriod(c, ENTITY, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
            FiscalYears.open(c, ENTITY, DEBUT_EXERCICE, FIN_EXERCICE, resultat.id(), ACTOR,
                             APPROVER);
            return null;
        });
        ecriture("ef-capital", D.minusDays(5), caisse, capital, "1000000");
        ecriture("ef-dep-a", D.minusDays(4), caisse, clientA, "200000");
        ecriture("ef-dep-b", D.minusDays(4), caisse, clientB, "50000");
        ecriture("ef-ret-b", D.minusDays(3), clientB, caisse, "80000");
        ecriture("ef-frais", D.minusDays(2), clientA, produits, "10000");
        ecriture("ef-salaires", D.minusDays(1), charges, caisse, "4000");
        ecriture("ef-engagement", D, engagement, contrepartie, "500000");

        bilan = activer(new StatementLayouts.Draft(ENTITY, Kind.BALANCE_SHEET, "BILAN-1", "Bilan",
            LocalDate.of(2026, 1, 1), null,
            List.of(detail(1, "A1", "Caisse", Direction.DEBIT),
                    detail(2, "A2", "Comptes ordinaires debiteurs", Direction.DEBIT),
                    total(3, "TA", "Total actif", Direction.DEBIT, List.of("A1", "A2"), List.of()),
                    detail(4, "P1", "Depots de la clientele", Direction.CREDIT),
                    detail(5, "P2", "Capital", Direction.CREDIT),
                    new Line(6, "PR", "Resultat de l'exercice", 1, LineKind.PROFIT_OR_LOSS,
                             Direction.CREDIT, null, null),
                    total(7, "TP", "Total passif", Direction.CREDIT, List.of("P1", "P2", "PR"),
                          List.of())),
            List.of(new Rule(1, "A2", AccountKind.CUSTOMER, null, Direction.DEBIT),
                    new Rule(2, "P1", AccountKind.CUSTOMER, null, Direction.CREDIT),
                    new Rule(3, "A1", null, "1-", null),
                    new Rule(4, "P2", null, "5-CAPITAL", null)),
            ACTOR));
        activer(new StatementLayouts.Draft(ENTITY, Kind.INCOME_STATEMENT, "RESULTAT-1",
            "Compte de resultat", LocalDate.of(2026, 1, 1), null,
            List.of(detail(1, "C1", "Charges generales", Direction.DEBIT),
                    detail(2, "R1", "Commissions", Direction.CREDIT),
                    total(3, "RES", "Resultat", Direction.CREDIT, List.of("R1"), List.of("C1"))),
            List.of(new Rule(1, "C1", null, "6-", null), new Rule(2, "R1", null, "7-", null)),
            ACTOR));
        activer(new StatementLayouts.Draft(ENTITY, Kind.OFF_BALANCE_SHEET, "HB-1", "Hors bilan",
            LocalDate.of(2026, 1, 1), null,
            List.of(detail(1, "E1", "Engagements donnes", Direction.DEBIT),
                    detail(2, "E2", "Contrepartie des engagements", Direction.CREDIT)),
            List.of(new Rule(1, "E1", null, "9-ENG", null), new Rule(2, "E2", null, "9-CTR", null)),
            ACTOR));
    }

    // ------------------------------------------------------------------ outillage

    private static Account compte(String code, AccountKind kind, NormalBalance normal,
                                  AccountNature nature) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normal, XOF, true,
                                      false, 1, AccountStatus.ACTIVE).withNature(nature);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static void ecriture(String key, LocalDate date, Account debit, Account credit,
                                 String amount) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of(amount, XOF), date, null),
                    PostingLine.credit(credit.id(), Money.of(amount, XOF), date, null))));
    }

    private static Line detail(int ordinal, String code, String label, Direction side) {
        return new Line(ordinal, code, label, 1, LineKind.DETAIL, side, null, null);
    }

    private static Line total(int ordinal, String code, String label, Direction side,
                              List<String> plus, List<String> minus) {
        return new Line(ordinal, code, label, 0, LineKind.TOTAL, side, plus, minus);
    }

    private static UUID activer(StatementLayouts.Draft draft) {
        return database.inTransaction(c -> {
            UUID id = StatementLayouts.createDraft(c, draft);
            StatementLayouts.activate(c, id, APPROVER);
            return id;
        });
    }

    private static Map<String, Money> montants(Statements.Statement statement) {
        return statement.lines().stream()
            .collect(Collectors.toMap(Statements.LineAmount::code, Statements.LineAmount::amount));
    }

    private static Money xof(String amount) {
        return Money.of(amount, XOF);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    @DisplayName("le bilan : chaque compte a sa rubrique selon sa nature, son code et le sens de son solde ; le resultat en cours au passif ; actif et passif egaux")
    void the_balance_sheet_balances() {
        Statements.Statement etat = database.inTransaction(c -> Statements.balanceSheet(c, ENTITY, D));
        Map<String, Money> montants = montants(etat);
        assertThat(etat.kind()).isEqualTo(Kind.BALANCE_SHEET);
        assertThat(etat.layoutId()).isEqualTo(bilan);
        assertThat(etat.currency()).isEqualTo("XOF");
        assertThat(etat.from()).isNull();
        assertThat(etat.to()).isEqualTo(D);
        assertThat(montants.get("A1")).isEqualTo(xof("1166000"));
        assertThat(montants.get("A2")).as("le client B, a decouvert, est un compte debiteur")
            .isEqualTo(xof("30000"));
        assertThat(montants.get("TA")).isEqualTo(xof("1196000"));
        assertThat(montants.get("P1")).as("le client A, net des frais").isEqualTo(xof("190000"));
        assertThat(montants.get("P2")).isEqualTo(xof("1000000"));
        assertThat(montants.get("PR")).as("commissions moins charges").isEqualTo(xof("6000"));
        assertThat(montants.get("TP")).isEqualTo(xof("1196000"));
        assertThat(etat.totalDebit()).isEqualTo(etat.totalCredit());
        assertThat(etat.net().isZero()).isTrue();
        assertThat(etat.anomalies()).isEmpty();
        assertThat(etat.consistent()).isTrue();
        assertThat(etat.lines()).extracting(Statements.LineAmount::code)
            .containsExactly("A1", "A2", "TA", "P1", "P2", "PR", "TP");
    }

    @Test
    @Order(2)
    @DisplayName("le compte de resultat presente les mouvements de l'exercice, et le hors bilan s'equilibre entre ses propres comptes")
    void income_and_off_balance_statements() {
        Statements.Statement resultat = database.inTransaction(c ->
            Statements.incomeStatement(c, ENTITY, DEBUT_EXERCICE, D));
        Map<String, Money> montants = montants(resultat);
        assertThat(montants.get("C1")).isEqualTo(xof("4000"));
        assertThat(montants.get("R1")).isEqualTo(xof("10000"));
        assertThat(montants.get("RES")).isEqualTo(xof("6000"));
        assertThat(resultat.net()).isEqualTo(xof("6000"));
        assertThat(resultat.from()).isEqualTo(DEBUT_EXERCICE);
        assertThat(resultat.consistent()).isTrue();

        Statements.Statement horsBilan = database.inTransaction(c ->
            Statements.offBalanceSheet(c, ENTITY, D));
        assertThat(montants(horsBilan).get("E1")).isEqualTo(xof("500000"));
        assertThat(montants(horsBilan).get("E2")).isEqualTo(xof("500000"));
        assertThat(horsBilan.net().isZero()).isTrue();
        assertThat(horsBilan.consistent()).isTrue();

        // Une ecriture ne melange pas le bilan et le hors bilan.
        assertThatThrownBy(() -> ecriture("ef-melange", D, engagement, caisse, "1"))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("hors bilan");
    }

    @Test
    @Order(3)
    @DisplayName("une maquette fausse n'entre pas en base ; l'activation exige un autre que le redacteur, et une seule maquette active par nature et par date")
    void layouts_are_checked_before_they_exist() {
        List<Rule> regle = List.of(new Rule(1, "A", null, "1-", null));
        assertThatThrownBy(() -> StatementLayouts.validate(new StatementLayouts.Draft(
                ENTITY, Kind.BALANCE_SHEET, "X", "X", D, null,
                List.of(total(1, "T", "Total", Direction.DEBIT, List.of("A"), List.of()),
                        detail(2, "A", "A", Direction.DEBIT)), regle, ACTOR)))
            .isInstanceOf(StatementLayouts.InvalidLayoutException.class)
            .hasMessageContaining("qui la suit");
        assertThatThrownBy(() -> StatementLayouts.validate(new StatementLayouts.Draft(
                ENTITY, Kind.BALANCE_SHEET, "X", "X", D, null,
                List.of(detail(1, "A", "A", Direction.DEBIT),
                        total(2, "T", "Total", Direction.DEBIT, List.of("A"), List.of())),
                List.of(new Rule(1, "T", null, "1-", null)), ACTOR)))
            .isInstanceOf(StatementLayouts.InvalidLayoutException.class)
            .hasMessageContaining("rubrique de detail");
        assertThatThrownBy(() -> StatementLayouts.validate(new StatementLayouts.Draft(
                ENTITY, Kind.BALANCE_SHEET, "X", "X", D, null,
                List.of(detail(1, "A", "A", Direction.DEBIT)),
                List.of(new Rule(1, "A", null, null, null)), ACTOR)))
            .isInstanceOf(StatementLayouts.InvalidLayoutException.class)
            .hasMessageContaining("au moins un critere");
        assertThatThrownBy(() -> StatementLayouts.validate(new StatementLayouts.Draft(
                ENTITY, Kind.INCOME_STATEMENT, "X", "X", D, null,
                List.of(detail(1, "A", "A", Direction.DEBIT),
                        new Line(2, "PR", "Resultat", 0, LineKind.PROFIT_OR_LOSS, Direction.CREDIT,
                                 null, null)), regle, ACTOR)))
            .isInstanceOf(StatementLayouts.InvalidLayoutException.class)
            .hasMessageContaining("qu'au bilan");

        // Le redacteur ne l'active pas ; et un second bilan actif a la meme date est refuse.
        StatementLayouts.Draft second = new StatementLayouts.Draft(ENTITY, Kind.BALANCE_SHEET,
            "BILAN-2", "Bilan bis", LocalDate.of(2026, 6, 1), null,
            List.of(detail(1, "A", "Tout", Direction.DEBIT)), regle, ACTOR);
        assertThatThrownBy(() -> database.inTransaction(c -> {
                UUID id = StatementLayouts.createDraft(c, second);
                StatementLayouts.activate(c, id, ACTOR);
                return null;
            })).hasStackTraceContaining("ck_layout_approval");
        assertThatThrownBy(() -> database.inTransaction(c -> {
                UUID id = StatementLayouts.createDraft(c, second);
                StatementLayouts.activate(c, id, APPROVER);
                return null;
            })).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("une seule a la fois")
            .hasStackTraceContaining("ex_layout_one_active");
        // Avant la validite de la maquette, il n'y a pas de bilan : pas de repli.
        assertThatThrownBy(() -> database.inTransaction(c ->
                Statements.balanceSheet(c, ENTITY, LocalDate.of(2025, 12, 31))))
            .isInstanceOf(StatementLayouts.NoLayoutException.class);
        StatementLayouts.Layout relu = database.inTransaction(c -> StatementLayouts.find(c, bilan))
            .orElseThrow();
        assertThat(relu.lines()).hasSize(7);
        assertThat(relu.rules()).hasSize(4);
        assertThat(relu.status()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(4)
    @DisplayName("un compte qu'aucune regle ne recoit et un resultat anterieur non clos sont nommes, et l'etat dit qu'il ne s'equilibre pas")
    void the_statement_names_what_it_cannot_present() {
        ecriture("ef-divers", D, divers, caisse, "7000");
        ecriture("ef-anterieur", LocalDate.of(2026, 3, 15), clientA, produits, "1000");

        Statements.Statement etat = database.inTransaction(c -> Statements.balanceSheet(c, ENTITY, D));
        assertThat(etat.consistent()).isFalse();
        assertThat(etat.anomalies()).hasSize(3);
        assertThat(etat.anomalies().get(0)).contains("3-DIVERS").contains("7000").contains("debiteur");
        assertThat(etat.anomalies().get(1)).contains("anterieur").contains("1000");
        assertThat(etat.anomalies().get(2)).contains("ne s'equilibre pas");
        assertThat(montants(etat).get("PR")).as("le resultat presente est celui de l'exercice")
            .isEqualTo(xof("6000"));
        assertThat(etat.net()).isEqualTo(xof("6000"));

        // Le compte de resultat de l'exercice ne voit pas l'ecriture anterieure.
        Statements.Statement resultat = database.inTransaction(c ->
            Statements.incomeStatement(c, ENTITY, DEBUT_EXERCICE, D));
        assertThat(resultat.net()).isEqualTo(xof("6000"));
        assertThat(resultat.consistent()).isTrue();
    }
}
