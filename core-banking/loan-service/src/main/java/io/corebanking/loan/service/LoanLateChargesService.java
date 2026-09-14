package io.corebanking.loan.service;

import io.corebanking.kernel.concurrent.Parallel;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.DueCategory;
import io.corebanking.loan.LateCharges;
import io.corebanking.loan.LateInterestBasis;
import io.corebanking.loan.LatePolicy;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interets de retard et penalites.
 *
 * <h2>Deux prelevements de nature differente</h2>
 *
 * <p>L'<b>interet de retard</b> court chaque jour sur l'impaye ; la <b>penalite</b> se percoit une
 * fois par echeance impayee. Les confondre produit soit une penalite quotidienne, soit aucun
 * interet de retard — et les deux erreurs se voient sur le releve du client bien avant d'etre
 * comprises.
 *
 * <h2>L'assiette est reconstituee jour par jour, jamais estimee sur l'etat courant</h2>
 *
 * <p>Un rattrapage de plusieurs jours doit facturer chaque journee sur l'impaye tel qu'il etait ce
 * jour-la. Calculer sur l'assiette d'aujourd'hui ignorerait les reglements intervenus entre-temps
 * et facturerait le client d'une dette qu'il avait deja reglee. La serie est donc reconstituee
 * depuis les creances et l'historique des imputations, avec leur date de valeur — c'est le meme
 * principe que le moteur d'interets courus, pour la meme raison.
 *
 * <h2>Aucune capitalisation</h2>
 *
 * <p>Les creances d'interet de retard et de penalite sont exclues de l'assiette <b>par
 * construction</b>. Faire porter interet aux interets echus est de l'anatocisme : encadre, voire
 * prohibe, dans la plupart des droits de la zone. L'exclusion n'est pas un parametre que l'on
 * pourrait inverser par inadvertance.
 *
 * <h2>Le cumul s'arrondit, pas la journee</h2>
 *
 * <p>Comme pour les interets courus : le cumul est tenu en precision interne, et seule la
 * difference entre son arrondi et ce qui a deja ete impute devient une ecriture. En XOF, un
 * arrondi quotidien perdrait quelques francs par jour et par contrat, systematiquement dans le
 * meme sens.
 */
public final class LoanLateChargesService {

    private static final int PARALLELISM = Parallel.defaultDegree("loan.parallelism");

    /**
     * Nombre maximal de journees rattrapees en une passe pour un contrat.
     *
     * <p>Un contrat oublie pendant des annees produirait sinon des dizaines de milliers de lignes
     * en une transaction. La borne transforme l'oubli en anomalie signalee.
     */
    private static final int MAX_CATCH_UP_DAYS = 400;

    private final Database database;
    private final PostingService postingService;
    private final int parallelism;

    public LoanLateChargesService(Database database, PostingService postingService) {
        this(database, postingService, PARALLELISM);
    }

    public LoanLateChargesService(Database database, PostingService postingService,
                                  int parallelism) {
        this.database = database;
        this.postingService = postingService;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Compte rendu d'une passe de facturation du retard. */
    public record Outcome(long contractsExamined, long contractsCharged, long penalties,
                          Money lateInterest, Money penaltyAmount, List<String> anomalies) {

        public Outcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    public Outcome charge(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                          UUID batchRunId) {
        List<LoanContract> contracts = database.inTransaction(
            c -> LoanStore.activeContracts(c, legalEntityId));

        Tally tally = new Tally();
        List<Runnable> tasks = new ArrayList<>(contracts.size());
        for (LoanContract contract : contracts) {
            tasks.add(() -> {
                try {
                    chargeOne(contract, businessDate, actorId, batchRunId, tally);
                } catch (RuntimeException e) {
                    tally.anomaly("credit " + contract.reference() + " : " + e.getMessage());
                }
            });
        }
        Parallel.runAll(tasks, parallelism);
        return tally.toOutcome(contracts.size());
    }

    private void chargeOne(LoanContract contract, LocalDate businessDate, UUID actorId,
                           UUID batchRunId, Tally tally) {
        database.inTransaction(c -> {
            ProductVersion product = ProductCatalog.resolveAt(
                c, contract.legalEntityId(), contract.productCode(), businessDate);
            LatePolicy policy = LoanCatalog.latePolicy(product, contract.currency()).orElse(null);
            if (policy == null) {
                return null;
            }

            List<LoanStore.OverdueLine> lines = LoanStore.overdueLines(c, contract.id(),
                                                                       contract.currency());
            if (lines.isEmpty()) {
                return null;
            }
            Map<UUID, List<LoanStore.AllocationHistory>> paid = paidByReceivable(
                LoanStore.allocationHistory(c, contract.id(), contract.currency()));

            Money penalty = chargePenalties(c, contract, policy, lines, paid, businessDate,
                                            batchRunId, tally);
            Accrued accrued = accrueLateInterest(c, contract, policy, lines, paid, businessDate,
                                                  batchRunId);

            UUID entryId = null;
            if (penalty.isPositive() || accrued.delta().isPositive()) {
                entryId = post(contract, product, penalty, accrued.delta(), businessDate, actorId,
                               batchRunId, LoanStore.isSuspended(c, contract.id()));
                tally.charged(accrued.delta(), penalty);
            }
            if (!accrued.days().isEmpty()) {
                // Les journees sont conservees meme lorsqu'aucune ecriture n'est produite : sans
                // elles, le calcul repartirait de zero au traitement suivant et refacturerait les
                // journees deja courues.
                LoanStore.insertLateAccruals(c, contract.id(), accrued.days(), accrued.delta(),
                                             entryId, entryId == null ? null : businessDate,
                                             batchRunId);
            }
            return null;
        });
    }

    /** Journees d'interet de retard calculees et part restant a imputer. */
    private record Accrued(Money delta, List<LoanStore.LateAccrual> days) {}

    // ------------------------------------------------------------------ penalites

    /**
     * Percoit la penalite des echeances qui viennent de franchir la franchise.
     *
     * <p>Une echeance ne produit qu'une penalite, quelle que soit la duree du retard : l'index
     * unique des creances le garantit, et la recherche prealable evite d'y buter.
     */
    private Money chargePenalties(Connection c, LoanContract contract, LatePolicy policy,
                                  List<LoanStore.OverdueLine> lines,
                                  Map<UUID, List<LoanStore.AllocationHistory>> paid,
                                  LocalDate businessDate, UUID batchRunId, Tally tally) {
        Money total = Money.zero(contract.currency());
        if (policy.penaltyMode() == io.corebanking.loan.PenaltyMode.NONE) {
            return total;
        }
        Map<Integer, Money> overdueByInstalment = new HashMap<>();
        Map<Integer, UUID> scheduleOf = new HashMap<>();
        Map<Integer, LocalDate> dueDateOf = new HashMap<>();

        for (LoanStore.OverdueLine line : lines) {
            if (!isPastGrace(line, policy, businessDate)) {
                continue;
            }
            Money remaining = remainingAt(line, paid, businessDate);
            if (!remaining.isPositive()) {
                continue;
            }
            overdueByInstalment.merge(line.instalmentNumber(), remaining, Money::plus);
            scheduleOf.putIfAbsent(line.instalmentNumber(), line.scheduleId());
            dueDateOf.putIfAbsent(line.instalmentNumber(), line.dueDate());
        }

        for (Map.Entry<Integer, Money> entry : overdueByInstalment.entrySet()) {
            int number = entry.getKey();
            UUID scheduleId = scheduleOf.get(number);
            if (LoanStore.findReceivable(c, contract.id(), scheduleId, number,
                                         DueCategory.PENALTIES).isPresent()) {
                continue;
            }
            Money amount = LateCharges.penalty(entry.getValue(), policy);
            if (!amount.isPositive()) {
                continue;
            }
            LoanStore.addReceivable(c, contract.id(), scheduleId, number, DueCategory.PENALTIES,
                                    dueDateOf.get(number), amount, batchRunId);
            total = total.plus(amount);
            tally.penalty();
        }
        return total;
    }

    // ------------------------------------------------------------------ interets de retard

    private Accrued accrueLateInterest(Connection c, LoanContract contract, LatePolicy policy,
                                       List<LoanStore.OverdueLine> lines,
                                       Map<UUID, List<LoanStore.AllocationHistory>> paid,
                                       LocalDate businessDate, UUID batchRunId) {
        Money zero = Money.zero(contract.currency());
        if (!policy.accruesInterest()) {
            return new Accrued(zero, List.of());
        }
        LoanStore.LateState state = LoanStore.lateState(c, contract.id(), contract.currency())
            .orElse(null);
        LocalDate from = state != null ? state.through().plusDays(1)
                                       : firstLateDay(lines, policy);
        if (from == null || from.isAfter(businessDate)) {
            return new Accrued(zero, List.of());
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, businessDate) > MAX_CATCH_UP_DAYS) {
            throw new IllegalStateException(
                "plus de " + MAX_CATCH_UP_DAYS + " journees de retard a rattraper depuis le "
                + from + ". Verifier l'exploitation du traitement avant de poursuivre");
        }

        Money cumulative = state != null ? state.cumulativePrecise() : zero;
        Money posted = state != null ? state.posted() : zero;
        List<LoanStore.LateAccrual> days = new ArrayList<>();

        for (LocalDate day = from; !day.isAfter(businessDate); day = day.plusDays(1)) {
            Money basis = basisOn(lines, paid, policy, day);
            Money precise = LateCharges.dailyInterest(basis, policy.lateInterestRatePercent(), day,
                                                      policy);
            cumulative = cumulative.plus(precise);
            days.add(new LoanStore.LateAccrual(day, basis, policy.lateInterestRatePercent(),
                                               policy.dayCount().dayFraction(day), precise,
                                               cumulative));
        }

        Money delta = LateCharges.postableDelta(cumulative, posted);
        if (delta.isPositive()) {
            lateInterestReceivable(c, contract, lines, policy, delta, batchRunId);
        }
        return new Accrued(delta.isPositive() ? delta : zero, days);
    }

    private void lateInterestReceivable(Connection c, LoanContract contract,
                                        List<LoanStore.OverdueLine> lines, LatePolicy policy,
                                        Money delta, UUID batchRunId) {
        LoanStore.findReceivable(c, contract.id(), null, 0, DueCategory.LATE_INTEREST)
            .ifPresentOrElse(
                id -> LoanStore.accrueLateInterest(c, id, delta),
                () -> LoanStore.addReceivable(c, contract.id(), null, 0, DueCategory.LATE_INTEREST,
                                              firstLateDay(lines, policy), delta, batchRunId));
    }

    /**
     * Assiette du jour : les creances passees la franchise, diminuees des reglements imputes
     * jusqu'a ce jour inclus.
     */
    private static Money basisOn(List<LoanStore.OverdueLine> lines,
                                 Map<UUID, List<LoanStore.AllocationHistory>> paid,
                                 LatePolicy policy, LocalDate day) {
        Money basis = Money.zero(policy.currency());
        for (LoanStore.OverdueLine line : lines) {
            if (!isPastGrace(line, policy, day)) {
                continue;
            }
            if (policy.basis() == LateInterestBasis.OVERDUE_PRINCIPAL
                && line.category() != DueCategory.PRINCIPAL) {
                continue;
            }
            basis = basis.plus(remainingAt(line, paid, day));
        }
        return basis;
    }

    private static boolean isPastGrace(LoanStore.OverdueLine line, LatePolicy policy,
                                       LocalDate day) {
        return line.dueDate().plusDays(policy.graceDays()).isBefore(day);
    }

    private static Money remainingAt(LoanStore.OverdueLine line,
                                     Map<UUID, List<LoanStore.AllocationHistory>> paid,
                                     LocalDate day) {
        Money remaining = line.originalAmount();
        for (LoanStore.AllocationHistory allocation : paid.getOrDefault(line.id(), List.of())) {
            if (!allocation.valueDate().isAfter(day)) {
                remaining = remaining.minus(allocation.amount());
            }
        }
        return remaining.isNegative() ? Money.zero(line.originalAmount().currency()) : remaining;
    }

    private static LocalDate firstLateDay(List<LoanStore.OverdueLine> lines, LatePolicy policy) {
        LocalDate first = null;
        for (LoanStore.OverdueLine line : lines) {
            LocalDate candidate = line.dueDate().plusDays(policy.graceDays() + 1L);
            if (first == null || candidate.isBefore(first)) {
                first = candidate;
            }
        }
        return first;
    }

    // ------------------------------------------------------------------ imputation

    private UUID post(LoanContract contract, ProductVersion product, Money penalty,
                      Money lateInterest, LocalDate businessDate, UUID actorId, UUID batchRunId,
                      boolean suspended) {
        EvaluationContext input = EvaluationContext.builder()
            .put("late_interest", lateInterest)
            .put("penalty", penalty)
            .build();
        List<PostingLine> lines = SchemaEngine.linesFor(
            LoanSchemas.lateCharges(contract.currency()), input,
            resolver(contract, product, suspended), contract.currency(), businessDate);

        return postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_LATE", contract.id(),
                                    businessDate),
            contract.legalEntityId(), businessDate, LoanSchemas.EVENT_LATE_CHARGES, actorId,
            batchRunId, lines)).entryId();
    }

    /**
     * @param suspended vrai lorsque le credit est classe au-dela du seuil : l'interet de retard
     *                  nait alors en interets reserves. La penalite, elle, reste en produits — ce
     *                  n'est pas un interet, et la suspension ne la concerne pas.
     */
    private AccountResolver resolver(LoanContract contract, ProductVersion product,
                                     boolean suspended) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> contract.loanAccountId();
            case PARAMETER -> switch (reference.value()) {
                case LoanSchemas.ROLE_ACCRUED -> LoanCatalog.accruedReceivable(product);
                case LoanSchemas.ROLE_LATE_INTEREST_INCOME -> suspended
                    ? LoanCatalog.reservedInterest(product)
                    : LoanCatalog.lateInterestIncome(product);
                case LoanSchemas.ROLE_PENALTY_INCOME -> LoanCatalog.penaltyIncome(product);
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage du produit " + product.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "reference non resolvable pour une charge de retard");
        };
    }

    private static Map<UUID, List<LoanStore.AllocationHistory>> paidByReceivable(
        List<LoanStore.AllocationHistory> history) {
        Map<UUID, List<LoanStore.AllocationHistory>> byReceivable = new HashMap<>();
        for (LoanStore.AllocationHistory allocation : history) {
            byReceivable.computeIfAbsent(allocation.receivableId(), id -> new ArrayList<>())
                .add(allocation);
        }
        return byReceivable;
    }

    // ------------------------------------------------------------------ compteurs

    private static final class Tally {
        private final List<String> anomalies = new ArrayList<>();
        private long charged;
        private long penalties;
        private Money lateInterest;
        private Money penaltyAmount;

        synchronized void charged(Money interest, Money penalty) {
            charged++;
            lateInterest = lateInterest == null ? interest : lateInterest.plus(interest);
            penaltyAmount = penaltyAmount == null ? penalty : penaltyAmount.plus(penalty);
        }

        synchronized void penalty() {
            penalties++;
        }

        synchronized void anomaly(String detail) {
            anomalies.add(detail);
        }

        synchronized Outcome toOutcome(long examined) {
            return new Outcome(examined, charged, penalties, lateInterest, penaltyAmount,
                               anomalies);
        }
    }
}
