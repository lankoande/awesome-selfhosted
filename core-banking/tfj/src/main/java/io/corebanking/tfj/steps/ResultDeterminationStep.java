package io.corebanking.tfj.steps;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Determination du resultat : chaque compte de resultat est solde, par agence et par devise, sur
 * le compte de resultat de l'exercice.
 *
 * <p>Les soldes sont lus dans le journal en date de fin d'exercice, jamais dans un cliche : une
 * ecriture par devise et par agence, equilibree par construction dans les deux dimensions, datee
 * de la fin d'exercice, imputee par le traitement — donc contre-passable avec lui. Un compte de
 * resultat tenu dans une autre devise que le compte de resultat de l'exercice n'est pas solde en
 * silence : l'etape le nomme et s'arrete.
 */
public final class ResultDeterminationStep implements TfjStep {

    public static final String TRANSACTION_TYPE = "YEAR_END_RESULT";

    private final Database database;
    private final PostingService postingService;

    public ResultDeterminationStep(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
    }

    @Override
    public String name() {
        return "RESULT_DETERMINATION";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        List<String> anomalies = new ArrayList<>();
        return database.inTransaction(c -> {
            FiscalYears.FiscalYear year = FiscalYears.endingOn(c, context.legalEntityId(),
                                                               context.businessDate())
                .orElse(null);
            if (year == null) {
                anomalies.add("Aucun exercice ne se termine le " + context.businessDate());
                return new StepResult(0, 0, anomalies);
            }
            Account result = Accounts.loadAll(c, Set.of(year.resultAccountId()))
                .get(year.resultAccountId());
            List<FiscalYears.ProfitAndLossBalance> balances =
                FiscalYears.profitAndLossBalances(c, context.legalEntityId(), year.end());

            // Une ecriture par devise et par agence.
            Map<String, List<FiscalYears.ProfitAndLossBalance>> groups = new LinkedHashMap<>();
            for (FiscalYears.ProfitAndLossBalance balance : balances) {
                if (!balance.currency().code().equals(result.currency().code())) {
                    anomalies.add("Compte de resultat " + balance.accountId() + " tenu en "
                                  + balance.currency().code() + " : le compte de resultat de "
                                  + "l'exercice est en " + result.currency().code()
                                  + ", aucun compte ne peut recevoir ce solde");
                    continue;
                }
                groups.computeIfAbsent(balance.currency().code() + "|" + balance.branchId(),
                                       key -> new ArrayList<>()).add(balance);
            }
            if (!anomalies.isEmpty()) {
                return new StepResult(balances.size(), 0, anomalies);
            }

            String narrative = "Determination du resultat de l'exercice du " + year.start()
                + " au " + year.end();
            long written = 0;
            for (List<FiscalYears.ProfitAndLossBalance> group : groups.values()) {
                UUID branch = group.get(0).branchId();
                List<PostingLine> lines = new ArrayList<>();
                Money net = Money.zero(result.currency());
                for (FiscalYears.ProfitAndLossBalance balance : group) {
                    Money amount = balance.signedBalance().abs();
                    // Un solde debiteur se solde par un credit, et inversement.
                    lines.add((balance.signedBalance().isPositive()
                               ? PostingLine.credit(balance.accountId(), amount, year.end(),
                                                    narrative)
                               : PostingLine.debit(balance.accountId(), amount, year.end(),
                                                   narrative)).withBranch(branch));
                    net = net.plus(balance.signedBalance());
                }
                // La contrepartie porte le resultat de l'agence : debitrice pour une perte.
                if (!net.isZero()) {
                    lines.add((net.isPositive()
                               ? PostingLine.debit(result.id(), net, year.end(), narrative)
                               : PostingLine.credit(result.id(), net.abs(), year.end(),
                                                    narrative)).withBranch(branch));
                }
                postingService.post(PostingCommand.batch(
                    IdempotencyKey.forBatch(context.runId().toString(), TRANSACTION_TYPE,
                                            group.get(0).currency().code(), branch),
                    context.legalEntityId(), year.end(), TRANSACTION_TYPE, context.actorId(),
                    context.runId(), lines));
                written++;
            }
            return new StepResult(balances.size(), written, anomalies);
        });
    }
}
