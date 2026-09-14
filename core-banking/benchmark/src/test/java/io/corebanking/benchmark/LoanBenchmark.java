package io.corebanking.benchmark;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.loan.service.LoanService;
import io.corebanking.loan.service.LoanStore;
import io.corebanking.product.ProductCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cout de l'etape d'exigibilite des credits, dans le cas le plus defavorable : tous les credits
 * echeancent le meme jour, et tous sont preleves.
 *
 * <p>Deux ecritures par contrat — la constatation des charges, puis le prelevement — sur des
 * comptes clients differents a chaque fois. Comme les commissions, cela ne s'agrege pas.
 *
 * <pre>mvn test -pl benchmark -Dtest=LoanBenchmark -Dbench.loans=500</pre>
 */
class LoanBenchmark extends BenchmarkBase {

    /** Un portefeuille de credits est d'un ordre de grandeur plus petit qu'un portefeuille de comptes. */
    private static final int TARGET_LOANS = 200_000;
    private static final int TARGET_MINUTES = 90;

    private static final LocalDate DISBURSED = DAY.minusDays(5);

    @Test
    @DisplayName("duree de l'exigibilite des credits et extrapolation a la volumetrie cible")
    void loan_due_duration() {
        int loanCount = sizing("loans", 500);

        Account caisse = gl("GL-CAISSE-L", NormalBalance.DEBIT, 64);
        Account creances = gl("GL-CREANCES-L", NormalBalance.DEBIT, 64);
        Account produits = gl("GL-PRODUITS-L", NormalBalance.CREDIT, 64);
        Account taxe = gl("GL-TAXE-L", NormalBalance.CREDIT, 64);

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, creances.id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(LoanCatalog.P_DIRECT_DEBIT, "true");
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "CRED-BENCH", "TERM_LOAN", "Credit amortissable", "XOF",
                DISBURSED.minusDays(1), null, parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });

        LoanService loanService = new LoanService(database, postingService);
        List<UUID> contracts = seedLoans(loanCount, loanService);
        analyze();

        line("");
        line("=== Exigibilite des credits ===");
        line("credits echeancant le jour meme : " + loanCount);

        long start = System.currentTimeMillis();
        LoanService.DueOutcome outcome = loanService.makeDue(ENTITY, DAY, ACTOR, UUID.randomUUID());
        long elapsed = System.currentTimeMillis() - start;

        line("echeances rendues exigibles : " + outcome.instalmentsMadeDue()
             + ", prelevements : " + outcome.collected()
             + ", anomalies : " + outcome.anomalies().size());
        line("duree : " + elapsed + " ms");

        double perLoanMillis = elapsed * 1.0 / loanCount;
        double projectedMinutes = perLoanMillis * TARGET_LOANS / 60_000.0;
        line(String.format("cout par credit      : %.3f ms", perLoanMillis));
        line(String.format("extrapolation %d k  : %.1f minutes (cible %d)",
                           TARGET_LOANS / 1000, projectedMinutes, TARGET_MINUTES));
        line(projectedMinutes <= TARGET_MINUTES
             ? "=> la cible est tenue."
             : String.format("=> CIBLE MANQUEE d'un facteur %.1f.",
                             projectedMinutes / TARGET_MINUTES));

        org.assertj.core.api.Assertions.assertThat(outcome.anomalies()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(outcome.instalmentsMadeDue())
            .isEqualTo(loanCount);
        org.assertj.core.api.Assertions.assertThat(outcome.collected()).isEqualTo(loanCount);
    }

    private static List<UUID> seedLoans(int count, LoanService loanService) {
        var schedule = ScheduleGenerator.generate(
            LoanTerms.of(Money.of("1000000", Currencies.XOF)).ratePercent("12").instalments(12)
                .disbursedOn(DISBURSED).firstDueDate(DAY).build());

        List<UUID> contracts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String suffix = String.format("%07d", index);
            Account pret = customerAccount("L-PRET-" + suffix, NormalBalance.DEBIT);
            Account courant = customerAccount("L-COURANT-" + suffix, NormalBalance.CREDIT);
            UUID contract = database.inTransaction(c -> LoanStore.createContract(
                c, new LoanStore.ContractDraft(ENTITY, "REF-" + suffix, "CRED-BENCH",
                                               Currencies.XOF, pret.id(), courant.id(),
                                               Money.of("1000000", Currencies.XOF), DISBURSED,
                                               ACTOR)));
            loanService.disburse(contract, schedule, ACTOR, APPROVER);
            contracts.add(contract);
        }
        return contracts;
    }

    private static Account customerAccount(String code, NormalBalance sens) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER, sens,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, account, DISBURSED);
            return null;
        });
        return account;
    }
}
