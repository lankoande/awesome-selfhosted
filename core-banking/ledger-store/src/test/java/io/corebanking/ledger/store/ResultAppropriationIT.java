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
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'affectation du resultat : a deux, sur un exercice clos, exactement le resultat, agence par agence. */
class ResultAppropriationIT extends LedgerTestBase {

    private static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate DEBUT = LocalDate.of(2025, 9, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 8, 31);
    private static final LocalDate AFFECTATION = LocalDate.of(2026, 9, 5);

    private static UUID siege;
    private static UUID agence;
    private static UUID exercice;
    private static UUID runId;
    private static Account resultat;
    private static Account reserves;
    private static Account report;
    private static Account produits;
    private static Account charges;
    private static Account caisse;

    @BeforeAll
    static void exercice() {
        Account liaison = newGlAccount("LIAISON-AG1", XOF, NormalBalance.DEBIT, 1);
        resultat = newGlAccount("RESULTAT", XOF, NormalBalance.CREDIT, 1);
        reserves = newGlAccount("RESERVES", XOF, NormalBalance.CREDIT, 1);
        report = newGlAccount("REPORT", XOF, NormalBalance.CREDIT, 1);
        caisse = newGlAccount("CAISSE", XOF, NormalBalance.DEBIT, 1);
        produits = pnl("PRODUITS", NormalBalance.CREDIT);
        charges = pnl("CHARGES", NormalBalance.DEBIT);
        database.inTransaction(c -> {
            siege = Branches.headOffice(c, ENTITY);
            agence = Branches.create(c, ENTITY, "AG1", "Agence 1", Branches.Kind.BRANCH, null,
                                     LocalDate.of(2026, 8, 1), Map.of(XOF, liaison.id()));
            exercice = FiscalYears.open(c, ENTITY, DEBUT, FIN, resultat.id(), ACTOR, APPROVER);
            return null;
        });
        // L'exercice : 3 000 de produits et 1 000 de charges au siege, 500 de produits en agence.
        ecriture("ra-1", LocalDate.of(2026, 8, 10), "FEE", caisse, produits, "3000", siege);
        ecriture("ra-2", LocalDate.of(2026, 8, 12), "EXPENSE", charges, caisse, "1000", siege);
        ecriture("ra-3", LocalDate.of(2026, 8, 15), "FEE", caisse, produits, "500", agence);
    }

    private static Account pnl(String code, NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, normal, XOF,
                                      true, false, 1, AccountStatus.ACTIVE)
            .withNature(AccountNature.PROFIT_AND_LOSS);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static void ecriture(String key, LocalDate date, String type, Account debit,
                                 Account credit, String amount, UUID branch) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, type, ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of(amount, XOF), date, null)
                        .withBranch(branch),
                    PostingLine.credit(credit.id(), Money.of(amount, XOF), date, null)
                        .withBranch(branch))));
    }

    /** Ce que la cloture annuelle ferait : le resultat determine par agence, l'exercice clos. */
    private static void cloturer() {
        runId = UUID.randomUUID();
        postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(runId.toString(), "YEAR_END_RESULT", "XOF", siege), ENTITY,
            FIN, FiscalYears.YEAR_END_RESULT, ACTOR, runId,
            List.of(PostingLine.debit(produits.id(), Money.of("3000", XOF), FIN, null)
                        .withBranch(siege),
                    PostingLine.credit(charges.id(), Money.of("1000", XOF), FIN, null)
                        .withBranch(siege),
                    PostingLine.credit(resultat.id(), Money.of("2000", XOF), FIN, null)
                        .withBranch(siege))));
        postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(runId.toString(), "YEAR_END_RESULT", "XOF", agence), ENTITY,
            FIN, FiscalYears.YEAR_END_RESULT, ACTOR, runId,
            List.of(PostingLine.debit(produits.id(), Money.of("500", XOF), FIN, null)
                        .withBranch(agence),
                    PostingLine.credit(resultat.id(), Money.of("500", XOF), FIN, null)
                        .withBranch(agence))));
        database.inTransaction(c -> { FiscalYears.close(c, exercice, runId); return null; });
    }

    private static FiscalYears.Appropriation decision(List<FiscalYears.Allocation> allocations) {
        return new FiscalYears.Appropriation(exercice, AFFECTATION, LocalDate.of(2026, 9, 3),
                                             "AGO du 3 septembre 2026", allocations, ACTOR,
                                             APPROVER);
    }

    private static FiscalYears.Allocation vers(Account account, String amount) {
        return new FiscalYears.Allocation(account.id(), Money.of(amount, XOF));
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static BigDecimal soldeParAgence(Account account, UUID branch) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)"
                + " FROM journal_line WHERE account_id = ? AND branch_id = ?")) {
                ps.setObject(1, account.id());
                ps.setObject(2, branch);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBigDecimal(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Solde par agence", e);
            }
        });
    }

    @Test
    @DisplayName("le resultat s'affecte une fois l'exercice clos, en totalite, sur des comptes de bilan, et le compte de resultat ne garde de solde dans aucune agence")
    void the_result_is_appropriated_exactly_once_and_clears_every_branch() {
        // Tant que l'exercice n'est pas clos, il n'y a pas de resultat.
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, decision(List.of(vers(reserves, "2500"))))))
            .isInstanceOf(FiscalYears.NotAppropriableException.class)
            .hasMessageContaining("n'est pas clos");
        java.util.Optional<Money> avant = database.inTransaction(c ->
            FiscalYears.netResult(c, FiscalYears.require(c, exercice)));
        assertThat(avant).isEmpty();

        cloturer();

        FiscalYears.FiscalYear clos = database.inTransaction(c -> FiscalYears.require(c, exercice));
        java.util.Optional<Money> net = database.inTransaction(c -> FiscalYears.netResult(c, clos));
        assertThat(net).contains(Money.of("2500", XOF));
        Map<UUID, Money> parAgence = database.inTransaction(c ->
            FiscalYears.resultByBranch(c, clos)).stream()
            .collect(java.util.stream.Collectors.toMap(FiscalYears.BranchResult::branchId,
                                                       FiscalYears.BranchResult::credit));
        assertThat(parAgence).containsEntry(siege, Money.of("2000", XOF))
            .containsEntry(agence, Money.of("500", XOF));

        // Ni plus ni moins que le resultat ; apres la fin de l'exercice ; sur des comptes de bilan.
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, decision(List.of(vers(reserves, "2000"))))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ni plus ni moins");
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, new FiscalYears.Appropriation(exercice, FIN,
                    LocalDate.of(2026, 9, 3), "AGO", List.of(vers(reserves, "2500")), ACTOR,
                    APPROVER))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("apres la fin");
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, decision(List.of(vers(produits, "2500"))))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("destination");
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, new FiscalYears.Appropriation(exercice, AFFECTATION,
                    LocalDate.of(2026, 9, 3), "AGO", List.of(vers(reserves, "2500")), ACTOR,
                    ACTOR))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThat(solde(resultat)).isEqualTo(Money.of("2500", XOF));

        // La decision : 15 % en reserve legale, le reste en report a nouveau.
        FiscalYears.AppropriationRecord affectation = database.inTransaction(c ->
            FiscalYears.appropriate(c, postingService,
                                    decision(List.of(vers(reserves, "375"), vers(report, "2125")))));
        assertThat(affectation.netResult()).isEqualTo(Money.of("2500", XOF));
        assertThat(affectation.reversed()).isFalse();
        assertThat(solde(resultat).isZero()).isTrue();
        assertThat(soldeParAgence(resultat, siege)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(soldeParAgence(resultat, agence)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(solde(reserves)).isEqualTo(Money.of("375", XOF));
        assertThat(solde(report)).isEqualTo(Money.of("2125", XOF));
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(c ->
            Reconciliation.allBlockingChecks(c, ENTITY));
        assertThat(ecarts).isEmpty();
        java.util.Optional<FiscalYears.AppropriationRecord> enVigueur = database.inTransaction(
            c -> FiscalYears.currentAppropriation(c, exercice));
        assertThat(enVigueur).map(FiscalYears.AppropriationRecord::entryId)
            .contains(affectation.entryId());

        // Une seule fois, tant que l'ecriture tient.
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.appropriate(
                c, postingService, decision(List.of(vers(reserves, "2500"))))))
            .isInstanceOf(FiscalYears.NotAppropriableException.class)
            .hasMessageContaining("deja affecte");

        // Contre-passee, l'affectation reste dans l'histoire et ne compte plus : on recommence.
        postingService.reverse(affectation.entryId(), AFFECTATION, LocalDate.of(2026, 9, 6),
                               IdempotencyKey.of("ra-rev"), "repartition erronee");
        java.util.Optional<FiscalYears.AppropriationRecord> apresExtourne = database.inTransaction(
            c -> FiscalYears.currentAppropriation(c, exercice));
        assertThat(apresExtourne).isEmpty();
        List<FiscalYears.AppropriationRecord> historique = database.inTransaction(
            c -> FiscalYears.appropriations(c, exercice));
        assertThat(historique).hasSize(1).allMatch(FiscalYears.AppropriationRecord::reversed);
        assertThat(solde(resultat)).isEqualTo(Money.of("2500", XOF));
        database.inTransaction(c -> FiscalYears.appropriate(
            c, postingService, decision(List.of(vers(reserves, "2500")))));
        assertThat(solde(resultat).isZero()).isTrue();
        assertThat(solde(reserves)).isEqualTo(Money.of("2500", XOF));
        List<FiscalYears.AppropriationRecord> deux = database.inTransaction(
            c -> FiscalYears.appropriations(c, exercice));
        assertThat(deux).hasSize(2);
    }

    @Test
    @DisplayName("deux affectations concurrentes du meme resultat : une seule passe, l'autre voit la premiere et est refusee")
    void concurrent_appropriations_are_serialised() throws Exception {
        // Une seconde entite, pour un exercice a elle : la premiere garde le sien.
        UUID entite = UUID.randomUUID();
        Account resultat2 = compteDe(entite, "RESULTAT-2", NormalBalance.CREDIT,
                                     AccountNature.BALANCE_SHEET);
        Account reserves2 = compteDe(entite, "RESERVES-2", NormalBalance.CREDIT,
                                     AccountNature.BALANCE_SHEET);
        Account produits2 = compteDe(entite, "PRODUITS-2", NormalBalance.CREDIT,
                                     AccountNature.PROFIT_AND_LOSS);
        Account caisse2 = compteDe(entite, "CAISSE-2", NormalBalance.DEBIT,
                                   AccountNature.BALANCE_SHEET);
        UUID siege2 = database.inTransaction(c -> Branches.headOffice(c, entite));
        UUID annee = database.inTransaction(c -> FiscalYears.open(
            c, entite, DEBUT, FIN, resultat2.id(), ACTOR, APPROVER));
        UUID run = UUID.randomUUID();
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("c-1"), entite, LocalDate.of(2026, 8, 10), "FEE", ACTOR,
            List.of(PostingLine.debit(caisse2.id(), Money.of("700", XOF), LocalDate.of(2026, 8, 10),
                                      null).withBranch(siege2),
                    PostingLine.credit(produits2.id(), Money.of("700", XOF),
                                       LocalDate.of(2026, 8, 10), null).withBranch(siege2))));
        postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(run.toString(), "YEAR_END_RESULT", "XOF", siege2), entite,
            FIN, FiscalYears.YEAR_END_RESULT, ACTOR, run,
            List.of(PostingLine.debit(produits2.id(), Money.of("700", XOF), FIN, null)
                        .withBranch(siege2),
                    PostingLine.credit(resultat2.id(), Money.of("700", XOF), FIN, null)
                        .withBranch(siege2))));
        database.inTransaction(c -> { FiscalYears.close(c, annee, run); return null; });

        // Deux comptables, la meme decision, au meme instant.
        var depart = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Object> tentative = () -> {
            depart.await();
            try {
                return database.inTransaction(c -> FiscalYears.appropriate(
                    c, postingService, new FiscalYears.Appropriation(
                        annee, AFFECTATION, LocalDate.of(2026, 9, 3), "AGO",
                        List.of(new FiscalYears.Allocation(reserves2.id(), Money.of("700", XOF))),
                        ACTOR, APPROVER)));
            } catch (RuntimeException e) {
                return e;
            }
        };
        var premiere = executor.submit(tentative);
        var seconde = executor.submit(tentative);
        depart.countDown();
        List<Object> issues = List.of(premiere.get(), seconde.get());
        executor.shutdown();

        assertThat(issues).filteredOn(o -> o instanceof FiscalYears.AppropriationRecord).hasSize(1);
        assertThat(issues).filteredOn(o -> o instanceof FiscalYears.NotAppropriableException)
            .hasSize(1)
            .allSatisfy(o -> assertThat(((RuntimeException) o).getMessage())
                .contains("deja affecte"));
        assertThat(solde(resultat2).isZero()).isTrue();
        assertThat(solde(reserves2)).isEqualTo(Money.of("700", XOF));
        List<FiscalYears.AppropriationRecord> une = database.inTransaction(
            c -> FiscalYears.appropriations(c, annee));
        assertThat(une).hasSize(1);
    }

    /** Un compte general d'une entite creee pour le test, avec sa periode d'aout et de septembre. */
    private static Account compteDe(UUID entite, String code, NormalBalance normal,
                                    AccountNature nature) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT 1 FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, entite);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        Entities.insertLegalEntity(c, entite, "E-" + entite.toString().substring(0, 8),
                                                   "Entite " + code, "CI", XOF, BUSINESS_DATE);
                        Entities.openPeriod(c, entite, LocalDate.of(2026, 8, 1),
                                            LocalDate.of(2026, 8, 31));
                        Entities.openPeriod(c, entite, LocalDate.of(2026, 9, 1),
                                            LocalDate.of(2026, 9, 30));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Entite de test", e);
            }
            Account account = new Account(UUID.randomUUID(), entite, code, AccountKind.GL, normal,
                                          XOF, true, false, 1, AccountStatus.ACTIVE)
                .withNature(nature);
            Accounts.create(c, account);
            return null;
        });
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT id FROM account WHERE legal_entity_id = ? AND code = ?")) {
                ps.setObject(1, entite);
                ps.setString(2, code);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    UUID id = rs.getObject(1, UUID.class);
                    return Accounts.loadAll(c, java.util.Set.of(id)).get(id);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Relecture du compte " + code, e);
            }
        });
    }
}
