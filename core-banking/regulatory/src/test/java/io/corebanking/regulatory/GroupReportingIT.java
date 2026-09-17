package io.corebanking.regulatory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.ledger.store.StatementLayouts;
import io.corebanking.ledger.store.StatementLayouts.Kind;
import io.corebanking.ledger.store.StatementLayouts.Line;
import io.corebanking.ledger.store.StatementLayouts.LineKind;
import io.corebanking.ledger.store.StatementLayouts.Rule;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La liasse et la consolidation : les deux productions qui regardent plus d'un etat a la fois.
 *
 * <p>Deux entites juridiques, chacune avec ses comptes et ses maquettes, et un perimetre qui les
 * reunit : c'est le decor minimal ou la consolidation veut dire quelque chose.
 */
class GroupReportingIT {

    private static EmbeddedPostgres postgres;
    private static Database database;
    private static JdbcPostingService postingService;

    private static final UUID MERE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID FILLE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate FIN = LocalDate.of(2026, 9, 30);

    private static Account caisseMere;
    private static Account caisseFille;
    private static Account creanceMere;
    private static Account detteFille;

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        SchemaMigrator.ensurePartitions(database, FIN.minusMonths(6), FIN.plusMonths(3));
        postingService = new JdbcPostingService(database);
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, MERE, "GROUPE-CI", "Banque mere", "CI", Currencies.XOF,
                                       FIN);
            Entities.insertLegalEntity(c, FILLE, "GROUPE-SN", "Filiale", "SN", Currencies.XOF,
                                       FIN);
            Entities.openPeriod(c, MERE, FIN.minusMonths(6), FIN.plusMonths(3));
            Entities.openPeriod(c, FILLE, FIN.minusMonths(6), FIN.plusMonths(3));
            return null;
        });

        caisseMere = compte(MERE, "1-CAISSE", AccountNature.BALANCE_SHEET, NormalBalance.DEBIT);
        Account capitalMere = compte(MERE, "5-CAPITAL", AccountNature.BALANCE_SHEET,
                                     NormalBalance.CREDIT);
        creanceMere = compte(MERE, "1-GROUPE", AccountNature.BALANCE_SHEET, NormalBalance.DEBIT);
        Account produitsMere = compte(MERE, "7-COMMISSIONS", AccountNature.PROFIT_AND_LOSS,
                                      NormalBalance.CREDIT);

        caisseFille = compte(FILLE, "1-CAISSE", AccountNature.BALANCE_SHEET, NormalBalance.DEBIT);
        Account capitalFille = compte(FILLE, "5-CAPITAL", AccountNature.BALANCE_SHEET,
                                      NormalBalance.CREDIT);
        detteFille = compte(FILLE, "5-GROUPE", AccountNature.BALANCE_SHEET, NormalBalance.CREDIT);
        Account produitsFille = compte(FILLE, "7-COMMISSIONS", AccountNature.PROFIT_AND_LOSS,
                                       NormalBalance.CREDIT);

        // Le bilan presente le resultat de l'exercice : sans exercice ouvert, il ne sait pas ou
        // le porter, et le dit.
        exercice(MERE, compte(MERE, "5-RESULTAT", AccountNature.BALANCE_SHEET,
                              NormalBalance.CREDIT));
        exercice(FILLE, compte(FILLE, "5-RESULTAT", AccountNature.BALANCE_SHEET,
                               NormalBalance.CREDIT));

        maquettes(MERE);
        maquettes(FILLE);

        // La mere : 1 000 000 de capital apporte en caisse, 200 000 de commissions.
        ecriture(MERE, "mere-capital", FIN.minusMonths(2), caisseMere, capitalMere, "1000000");
        ecriture(MERE, "mere-produits", FIN.minusMonths(1), caisseMere, produitsMere, "200000");
        // La fille : 400 000 de capital, 50 000 de commissions.
        ecriture(FILLE, "fille-capital", FIN.minusMonths(2), caisseFille, capitalFille, "400000");
        ecriture(FILLE, "fille-produits", FIN.minusMonths(1), caisseFille, produitsFille, "50000");
        // Un pret de la mere a la fille : creance d'un cote, dette de l'autre. C'est ce qui
        // s'elimine.
        ecriture(MERE, "mere-pret", FIN.minusDays(20), creanceMere, caisseMere, "300000");
        ecriture(FILLE, "fille-emprunt", FIN.minusDays(20), caisseFille, detteFille, "300000");
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("la liasse produit ses etats et rapproche le resultat : le compte de resultat et le bilan disent la meme chose")
    void the_pack_reconciles_the_result_between_its_statements() {
        database.inEntity(MERE, c -> StatementPacks.declare(c, new StatementPacks.Draft(
            MERE, "LIASSE", "Liasse annuelle",
            List.of(Kind.BALANCE_SHEET, Kind.INCOME_STATEMENT), FIN.minusMonths(6), null,
            ACTOR, APPROVER)));
        UUID declaration = declarer("LIASSE-DECL", RegulatoryDeclarations.Method.STATEMENT_PACK,
                                    "LIASSE");
        UUID etat = new ReportingService(database).produce(MERE, declaration, FIN, FIN.plusDays(1),
                                                           ACTOR);

        ReportFilings.Filing filing = database.inEntity(MERE,
            c -> ReportFilings.require(c, etat));
        assertThat(filing.anomalies()).as("les deux etats se tiennent").isEmpty();
        assertThat(filing.lines()).extracting(ReportFilings.Line::subjectReference)
            .contains("BALANCE_SHEET/PR", "INCOME_STATEMENT/RES");
        // Le resultat porte au bilan est celui du compte de resultat : 200 000 de commissions.
        ReportFilings.Line auBilan = ligne(filing, "BALANCE_SHEET/PR");
        assertThat(auBilan.amount()).isEqualTo(xof("200000"));

        // Un etat sans anomalie se transmet.
        ReportFilings.Filing transmis = database.inEntity(MERE, c -> ReportFilings.transmit(
            c, etat, FIN.plusDays(2), "COMMISSION-2026-09", ACTOR, APPROVER));
        assertThat(transmis.transmitted()).isTrue();
    }

    @Test
    @DisplayName("une liasse cite au moins le bilan et le compte de resultat, et ne cite pas deux fois le meme etat")
    void a_pack_is_declared_as_a_whole() {
        assertThatThrownBy(() -> new StatementPacks.Draft(MERE, "PARTIELLE", "Sans resultat",
                List.of(Kind.BALANCE_SHEET), FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("au moins le bilan et le compte de resultat");
        assertThatThrownBy(() -> new StatementPacks.Draft(MERE, "DOUBLE", "Deux fois le bilan",
                List.of(Kind.BALANCE_SHEET, Kind.BALANCE_SHEET, Kind.INCOME_STATEMENT),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deux fois le meme etat");
        assertThatThrownBy(() -> new StatementPacks.Draft(MERE, "SEULE", "Decidee seule",
                List.of(Kind.BALANCE_SHEET, Kind.INCOME_STATEMENT), FIN.minusMonths(6), null,
                ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
    }

    @Test
    @DisplayName("la consolidation agrege chaque entite dans sa propre portee, applique la quote-part et elimine ce qui se fait face")
    void consolidation_aggregates_each_entity_in_its_own_scope() {
        UUID scope = database.inEntity(MERE, c -> {
            UUID id = ConsolidationScopes.declare(c, new ConsolidationScopes.Draft(
                MERE, "GROUPE", "Perimetre du groupe", "XOF",
                List.of(new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                        new BigDecimal("100")),
                        new ConsolidationScopes.Member(FILLE, ConsolidationScopes.Method.FULL,
                                                        new BigDecimal("100"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER));
            ConsolidationScopes.eliminate(c, id, "Pret intra-groupe", MERE, creanceMere.id(),
                                          FILLE, detteFille.id());
            return id;
        });
        assertThat(scope).isNotNull();

        UUID declaration = declarer("CONSO-DECL",
                                    RegulatoryDeclarations.Method.CONSOLIDATED_STATEMENTS,
                                    "GROUPE");
        UUID etat = new ReportingService(database).produce(MERE, declaration, FIN, FIN.plusDays(1),
                                                           ACTOR);
        ReportFilings.Filing filing = database.inEntity(MERE, c -> ReportFilings.require(c, etat));

        // La caisse du groupe : 900 000 chez la mere (1 000 000 + 200 000 - 300 000) et
        // 750 000 chez la fille (400 000 + 50 000 + 300 000).
        assertThat(ligne(filing, "BALANCE_SHEET/A1").amount()).isEqualTo(xof("1650000"));
        // Le capital du groupe : les deux capitaux agreges, sans elimination declaree.
        assertThat(ligne(filing, "BALANCE_SHEET/P2").amount()).isEqualTo(xof("1400000"));
        // Les commissions des deux entites.
        assertThat(ligne(filing, "INCOME_STATEMENT/R1").amount()).isEqualTo(xof("250000"));
        // Ce qui se fait face est elimine, et le montant elimine est dit.
        assertThat(ligne(filing, "ELIMINATIONS").amount()).isEqualTo(xof("-300000"));
        assertThat(filing.anomalies()).as("les comptes intra-groupe se repondent").isEmpty();
    }

    @Test
    @DisplayName("un ecart d'elimination est nomme, et l'etat qui le porte ne se transmet pas")
    void an_elimination_gap_is_named_and_blocks_transmission() {
        UUID scope = database.inEntity(MERE, c -> {
            UUID id = ConsolidationScopes.declare(c, new ConsolidationScopes.Draft(
                MERE, "GROUPE-ECART", "Perimetre avec ecart", "XOF",
                List.of(new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                        new BigDecimal("100")),
                        new ConsolidationScopes.Member(FILLE, ConsolidationScopes.Method.FULL,
                                                        new BigDecimal("100"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER));
            // Le compte de caisse de la fille ne fait face a rien : l'ecart sera flagrant.
            ConsolidationScopes.eliminate(c, id, "Elimination boiteuse", MERE, creanceMere.id(),
                                          FILLE, caisseFille.id());
            return id;
        });
        assertThat(scope).isNotNull();

        UUID declaration = declarer("CONSO-ECART",
                                    RegulatoryDeclarations.Method.CONSOLIDATED_STATEMENTS,
                                    "GROUPE-ECART");
        UUID etat = new ReportingService(database).produce(MERE, declaration, FIN, FIN.plusDays(1),
                                                           ACTOR);
        ReportFilings.Filing filing = database.inEntity(MERE, c -> ReportFilings.require(c, etat));
        assertThat(filing.anomalies()).anyMatch(a -> a.contains("Elimination boiteuse")
                                                     && a.contains("un seul cote"));

        assertThatThrownBy(() -> database.inEntity(MERE, c -> ReportFilings.transmit(
                c, etat, FIN.plusDays(2), "CONSO-1", ACTOR, APPROVER)))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("on sait qu'ils sont faux");
    }

    @Test
    @DisplayName("un perimetre porte l'entite qui publie, une seule fois chacune, et l'integration globale reprend tout")
    void a_scope_is_declared_coherently() {
        assertThatThrownBy(() -> new ConsolidationScopes.Draft(MERE, "SANS-TETE", "Sans la mere",
                "XOF", List.of(new ConsolidationScopes.Member(FILLE,
                    ConsolidationScopes.Method.FULL, new BigDecimal("100"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sans sa tete");

        assertThatThrownBy(() -> new ConsolidationScopes.Draft(MERE, "DOUBLE", "Deux fois",
                "XOF", List.of(
                    new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                   new BigDecimal("100")),
                    new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                   new BigDecimal("100"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deux fois");

        assertThatThrownBy(() -> new ConsolidationScopes.Draft(MERE, "PARTIELLE", "Globale a 60 %",
                "XOF", List.of(
                    new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                   new BigDecimal("100")),
                    new ConsolidationScopes.Member(FILLE, ConsolidationScopes.Method.FULL,
                                                   new BigDecimal("60"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("interets minoritaires");
    }

    @Test
    @DisplayName("la mise en equivalence est nommee, pas agregee : le socle ne presente pas une quote-part qu'il ne calcule pas")
    void equity_method_is_named_not_aggregated() {
        UUID scope = database.inEntity(MERE, c -> ConsolidationScopes.declare(c,
            new ConsolidationScopes.Draft(MERE, "GROUPE-MEE", "Avec mise en equivalence", "XOF",
                List.of(new ConsolidationScopes.Member(MERE, ConsolidationScopes.Method.FULL,
                                                        new BigDecimal("100")),
                        new ConsolidationScopes.Member(FILLE, ConsolidationScopes.Method.EQUITY,
                                                        new BigDecimal("30"))),
                FIN.minusMonths(6), null, ACTOR, APPROVER)));
        assertThat(scope).isNotNull();

        UUID declaration = declarer("CONSO-MEE",
                                    RegulatoryDeclarations.Method.CONSOLIDATED_STATEMENTS,
                                    "GROUPE-MEE");
        UUID etat = new ReportingService(database).produce(MERE, declaration, FIN, FIN.plusDays(1),
                                                           ACTOR);
        ReportFilings.Filing filing = database.inEntity(MERE, c -> ReportFilings.require(c, etat));

        assertThat(filing.anomalies()).anyMatch(a -> a.contains("mise en equivalence"));
        // Seule la mere est agregee : sa caisse, pas celle du groupe.
        assertThat(ligne(filing, "BALANCE_SHEET/A1").amount()).isEqualTo(xof("900000"));
    }

    // ------------------------------------------------------------------ outillage

    private static UUID declarer(String code, RegulatoryDeclarations.Method method,
                                 String subject) {
        return database.inEntity(MERE, c -> RegulatoryDeclarations.declare(c,
            new RegulatoryDeclarations.Draft(MERE, code, code,
                RegulatoryDeclarations.Recipient.BANKING_COMMISSION, method,
                RegulatoryDeclarations.Frequency.MONTHLY, 30, null, subject,
                FIN.minusMonths(6), null, ACTOR, APPROVER)));
    }

    private static ReportFilings.Line ligne(ReportFilings.Filing filing, String reference) {
        return filing.lines().stream().filter(l -> reference.equals(l.subjectReference()))
            .findFirst().orElseThrow(() -> new AssertionError("ligne absente : " + reference));
    }

    private static Account compte(UUID entity, String code, AccountNature nature,
                                  NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), entity, code, AccountKind.GL, normal,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE)
            .withNature(nature);
        database.inEntity(entity, c -> {
            Accounts.create(c, account, FIN.minusMonths(6));
            return null;
        });
        return account;
    }

    private static void ecriture(UUID entity, String key, LocalDate date, Account debit,
                                 Account credit, String montant) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), entity, date, "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), xof(montant), date, null),
                    PostingLine.credit(credit.id(), xof(montant), date, null))));
    }

    private static void exercice(UUID entity, Account resultat) {
        database.inEntity(entity, c -> io.corebanking.ledger.store.FiscalYears.open(
            c, entity, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), resultat.id(),
            ACTOR, APPROVER));
    }

    /** Les maquettes minimales d'une entite : un bilan et un compte de resultat. */
    private static void maquettes(UUID entity) {
        database.inEntity(entity, c -> {
            UUID bilan = StatementLayouts.createDraft(c, new StatementLayouts.Draft(
                entity, Kind.BALANCE_SHEET, "BILAN", "Bilan", FIN.minusMonths(6), null,
                List.of(new Line(1, "A1", "Caisse", 1, LineKind.DETAIL, Direction.DEBIT, null,
                                 null),
                        new Line(2, "A2", "Creances groupe", 1, LineKind.DETAIL, Direction.DEBIT,
                                 null, null),
                        new Line(3, "P1", "Dettes groupe", 1, LineKind.DETAIL, Direction.CREDIT,
                                 null, null),
                        new Line(4, "P2", "Capital", 1, LineKind.DETAIL, Direction.CREDIT, null,
                                 null),
                        new Line(5, "PR", "Resultat de l'exercice", 1, LineKind.PROFIT_OR_LOSS,
                                 Direction.CREDIT, null, null)),
                List.of(new Rule(1, "A2", null, "1-GROUPE", null),
                        new Rule(2, "P1", null, "5-GROUPE", null),
                        new Rule(3, "A1", null, "1-", null),
                        new Rule(4, "P2", null, "5-CAPITAL", null)),
                ACTOR));
            StatementLayouts.activate(c, bilan, APPROVER);
            UUID resultat = StatementLayouts.createDraft(c, new StatementLayouts.Draft(
                entity, Kind.INCOME_STATEMENT, "RESULTAT", "Compte de resultat",
                FIN.minusMonths(6), null,
                List.of(new Line(1, "C1", "Charges", 1, LineKind.DETAIL, Direction.DEBIT, null,
                                 null),
                        new Line(2, "R1", "Commissions", 1, LineKind.DETAIL, Direction.CREDIT,
                                 null, null),
                        new Line(3, "RES", "Resultat", 0, LineKind.TOTAL, Direction.CREDIT,
                                 List.of("R1"), List.of("C1"))),
                List.of(new Rule(1, "C1", null, "6-", null), new Rule(2, "R1", null, "7-", null)),
                ACTOR));
            StatementLayouts.activate(c, resultat, APPROVER);
            return null;
        });
    }

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }
}
