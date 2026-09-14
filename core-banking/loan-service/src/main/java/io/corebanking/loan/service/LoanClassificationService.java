package io.corebanking.loan.service;

import io.corebanking.kernel.concurrent.Parallel;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.Contagion;
import io.corebanking.loan.Provisioning;
import io.corebanking.loan.RiskBucket;
import io.corebanking.loan.RiskGrid;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classification des credits, provisionnement et suspension des interets.
 *
 * <h2>Trois operations qui se suivent, et dont l'ordre n'est pas indifferent</h2>
 *
 * <ol>
 *   <li><b>Classer</b> chaque credit selon l'age de son impaye le plus ancien.</li>
 *   <li><b>Propager</b> le declassement a tous les encours du meme client, lorsque le profil
 *       l'impose. Un client qui ne rembourse plus l'un de ses credits ne presente pas un risque
 *       different sur les autres ; l'ignorer sous-estime le risque exactement la ou il se
 *       materialise.</li>
 *   <li><b>Doter ou reprendre</b> la provision, et <b>suspendre</b> les interets au franchissement
 *       du seuil.</li>
 * </ol>
 *
 * <p>La contagion vient apres le vieillissement et avant le provisionnement : provisionner sur la
 * classe propre puis propager laisserait chaque credit provisionne a son propre niveau, ce qui est
 * precisement ce que la contagion refuse.
 *
 * <h2>La suspension des interets</h2>
 *
 * <p>L'etape que les developpements maison omettent le plus souvent, et dont l'absence est une
 * non-conformite directe : la banque continue de porter en produits des interets qu'elle ne
 * percevra pas, et surevalue son produit net bancaire. Ici, au franchissement du seuil, les
 * interets deja constates et encore impayes sortent du resultat vers un compte d'<b>interets
 * reserves</b> ; et les interets constates ensuite y naissent directement. Ils ne disparaissent
 * pas : ils reviendront au compte de produits s'ils sont un jour encaisses.
 *
 * <h2>Ce que l'etape ne fait pas</h2>
 *
 * <p>Le retour a meilleure fortune — la reclassification en sain apres une periode de
 * regularisation, avec son delai d'observation — n'est pas traite. Un credit regularise voit sa
 * provision reprise parce que son retard tombe a zero, mais aucun delai d'observation ne
 * s'applique. C'est une simplification, et elle est favorable a l'emprunteur.
 */
public final class LoanClassificationService {

    /**
     * Le classement est global — la contagion regarde tout le portefeuille d'un client — mais le
     * provisionnement de chaque credit est independant des autres et se parallelise.
     */
    private static final int PARALLELISM = Parallel.defaultDegree("loan.parallelism");

    private final Database database;
    private final PostingService postingService;
    private final LoanService loanService;
    private final int parallelism;

    public LoanClassificationService(Database database, PostingService postingService,
                                     LoanService loanService) {
        this(database, postingService, loanService, PARALLELISM);
    }

    public LoanClassificationService(Database database, PostingService postingService,
                                     LoanService loanService, int parallelism) {
        this.database = database;
        this.postingService = postingService;
        this.loanService = loanService;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Compte rendu d'une passe de classification. */
    public record Outcome(
        long contractsExamined, long downgraded, long contaminated, long suspended,
        Money provisionCharged, Money provisionReleased, Money interestSuspended,
        List<String> anomalies) {

        public Outcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    /** Classement d'un credit avant imputation. */
    private record Assessment(LoanContract contract, ProductVersion product, RiskGrid grid,
                              RiskBucket bucket, long daysPastDue, boolean contaminated) {}

    public Outcome classify(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                            UUID batchRunId) {
        List<LoanContract> contracts = database.inTransaction(
            c -> LoanStore.activeContracts(c, legalEntityId));
        List<String> anomalies = new ArrayList<>();

        Map<UUID, Assessment> byContract = new LinkedHashMap<>();
        for (LoanContract contract : contracts) {
            try {
                assess(contract, businessDate).ifPresent(a -> byContract.put(contract.id(), a));
            } catch (RuntimeException e) {
                anomalies.add("credit " + contract.reference() + " : " + e.getMessage());
            }
        }
        long contaminated = propagate(byContract);

        Tally tally = new Tally(anomalies, contaminated);
        List<Runnable> tasks = new ArrayList<>(byContract.size());
        for (Assessment assessment : byContract.values()) {
            tasks.add(() -> {
                try {
                    settle(assessment, businessDate, actorId, batchRunId, tally);
                } catch (RuntimeException e) {
                    tally.anomaly("credit " + assessment.contract().reference() + " : "
                                  + e.getMessage());
                }
            });
        }
        Parallel.runAll(tasks, parallelism);
        return tally.toOutcome(contracts.size());
    }

    // ------------------------------------------------------------------ classement

    private java.util.Optional<Assessment> assess(LoanContract contract, LocalDate businessDate) {
        return database.inTransaction(c -> {
            ProductVersion product = ProductCatalog.resolveAt(
                c, contract.legalEntityId(), contract.productCode(), businessDate);
            String profile = LoanCatalog.riskProfile(product).orElse(null);
            if (profile == null) {
                return java.util.Optional.<Assessment>empty();
            }
            RiskGrid grid = RiskProfiles.resolveAt(c, contract.legalEntityId(), profile,
                                                   businessDate);
            long daysPastDue = loanService.daysPastDue(contract.id(), businessDate);
            return java.util.Optional.of(new Assessment(contract, product, grid,
                                                        grid.bucketFor(daysPastDue), daysPastDue,
                                                        false));
        });
    }

    /**
     * Propage le declassement aux encours du meme client.
     *
     * <p>Deux credits d'un meme client peuvent relever de profils differents — un credit habitat et
     * un decouvert n'ont pas la meme grille. La contagion se fait alors sur le <b>rang</b> de
     * degradation, et non sur le code de classe : c'est ce que le rang est fait pour porter, les
     * codes variant d'un profil et d'un pays a l'autre.
     */
    private long propagate(Map<UUID, Assessment> byContract) {
        List<UUID> contractIds = new ArrayList<>(byContract.keySet());
        if (contractIds.isEmpty()) {
            return 0;
        }
        Map<UUID, UUID> customers = database.inTransaction(
            c -> LoanStore.customersOf(c, contractIds));

        Map<UUID, Integer> worstByCustomer = new LinkedHashMap<>();
        for (Map.Entry<UUID, Assessment> entry : byContract.entrySet()) {
            UUID customer = customers.get(entry.getKey());
            if (customer == null || entry.getValue().grid().contagion() == Contagion.NONE) {
                continue;
            }
            worstByCustomer.merge(customer, entry.getValue().bucket().ordinal(), Math::max);
        }

        long contaminated = 0;
        for (Map.Entry<UUID, Assessment> entry : byContract.entrySet()) {
            Assessment assessment = entry.getValue();
            UUID customer = customers.get(entry.getKey());
            if (customer == null || assessment.grid().contagion() == Contagion.NONE) {
                continue;
            }
            int worst = worstByCustomer.getOrDefault(customer, assessment.bucket().ordinal());
            if (worst <= assessment.bucket().ordinal()) {
                continue;
            }
            // Le rang le plus degrade du client, ramene dans la grille propre du credit. Un rang
            // hors grille est ramene a la classe la plus degradee de celle-ci.
            List<RiskBucket> buckets = assessment.grid().buckets();
            RiskBucket target = buckets.get(Math.min(worst, buckets.size() - 1));
            entry.setValue(new Assessment(assessment.contract(), assessment.product(),
                                          assessment.grid(), target, assessment.daysPastDue(),
                                          true));
            contaminated++;
        }
        return contaminated;
    }

    // ------------------------------------------------------------------ imputation

    private void settle(Assessment assessment, LocalDate businessDate, UUID actorId,
                        UUID batchRunId, Tally tally) {
        database.inTransaction(c -> {
            LoanContract contract = assessment.contract();
            LoanStore.ClassificationState previous =
                LoanStore.lastClassification(c, contract.id(), contract.currency()).orElse(null);
            Money alreadyProvisioned = previous != null ? previous.provisioned()
                                                        : Money.zero(contract.currency());

            Money exposure = LoanStore.exposureOf(c, contract);
            Money collateral = LoanStore.eligibleCollateral(c, contract.id(), contract.currency(),
                                                            businessDate);
            Provisioning.Provision provision = Provisioning.compute(exposure, collateral,
                                                                    assessment.bucket());
            Money delta = provision.deltaFrom(alreadyProvisioned);

            boolean suspended = assessment.grid().suspendsAt(assessment.bucket());
            boolean crossing = suspended && (previous == null || !previous.suspended());
            LoanStore.RecognisedInterest toReserve = crossing
                ? LoanStore.unpaidRecognisedInterest(c, contract.id(), contract.currency())
                : new LoanStore.RecognisedInterest(Money.zero(contract.currency()),
                                                   Money.zero(contract.currency()));

            UUID entryId = null;
            if (delta.isPositive()) {
                entryId = post(contract, assessment.product(),
                               LoanSchemas.provisionCharge(contract.currency()),
                               LoanSchemas.EVENT_PROVISION_CHARGE, delta, businessDate, actorId,
                               batchRunId, "PROV");
                tally.charged(delta);
            } else if (delta.isNegative()) {
                entryId = post(contract, assessment.product(),
                               LoanSchemas.provisionRelease(contract.currency()),
                               LoanSchemas.EVENT_PROVISION_RELEASE, delta.negate(), businessDate,
                               actorId, batchRunId, "PROV");
                tally.released(delta.negate());
            }
            if (toReserve.isPositive()) {
                EvaluationContext input = EvaluationContext.builder()
                    .put("interest", toReserve.contractual())
                    .put("late_interest", toReserve.late())
                    .build();
                postWith(contract, assessment.product(),
                         LoanSchemas.interestSuspension(contract.currency()), input,
                         LoanSchemas.EVENT_INTEREST_SUSPENSION, businessDate, actorId, batchRunId,
                         "SUSP");
                tally.suspended(toReserve.total());
            }
            // Un credit classe sain pour la premiere fois n'est pas declasse : le compteur ne
            // retient que les degradations reelles, sans quoi le premier arrete d'un portefeuille
            // signalerait un declassement general.
            int previousOrdinal = previous != null ? previous.ordinal() : 0;
            if (previousOrdinal < assessment.bucket().ordinal()) {
                tally.downgraded();
            }

            LoanStore.recordClassification(c, new LoanStore.ClassificationRow(
                contract.id(), businessDate, assessment.daysPastDue(), assessment.bucket().code(),
                assessment.bucket().ordinal(), assessment.bucket().performing(),
                assessment.contaminated() ? "CONTAGION" : "AGEING", exposure,
                provision.retainedCollateral(), provision.base(),
                assessment.bucket().provisionRatePercent(), provision.amount(), delta, suspended,
                toReserve.total(), entryId, batchRunId));
            return null;
        });
    }

    private UUID post(LoanContract contract, ProductVersion product, EventTemplate template,
                      String transactionType, Money amount, LocalDate businessDate, UUID actorId,
                      UUID batchRunId, String keyPart) {
        return postWith(contract, product, template,
                        EvaluationContext.builder().put("amount", amount).build(), transactionType,
                        businessDate, actorId, batchRunId, keyPart);
    }

    private UUID postWith(LoanContract contract, ProductVersion product, EventTemplate template,
                          EvaluationContext input, String transactionType, LocalDate businessDate,
                          UUID actorId, UUID batchRunId, String keyPart) {
        List<PostingLine> lines = SchemaEngine.linesFor(template, input, resolver(contract, product),
                                                        contract.currency(), businessDate);
        return postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_" + keyPart, contract.id(),
                                    businessDate),
            contract.legalEntityId(), businessDate, transactionType, actorId, batchRunId, lines))
            .entryId();
    }

    private AccountResolver resolver(LoanContract contract, ProductVersion product) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> contract.loanAccountId();
            case PARAMETER -> switch (reference.value()) {
                case LoanSchemas.ROLE_PROVISION_EXPENSE -> LoanCatalog.provisionExpense(product);
                case LoanSchemas.ROLE_PROVISION_ALLOWANCE ->
                    LoanCatalog.provisionAllowance(product);
                case LoanSchemas.ROLE_PROVISION_RELEASE -> LoanCatalog.provisionRelease(product);
                case LoanSchemas.ROLE_INTEREST_INCOME -> LoanCatalog.interestIncome(product);
                case LoanSchemas.ROLE_LATE_INTEREST_INCOME ->
                    LoanCatalog.lateInterestIncome(product);
                case LoanSchemas.ROLE_RESERVED_INTEREST -> LoanCatalog.reservedInterest(product);
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage du produit " + product.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "reference non resolvable pour une ecriture de provision");
        };
    }

    // ------------------------------------------------------------------ compteurs

    private static final class Tally {
        private final List<String> anomalies;
        private final long contaminated;
        private long downgraded;
        private long suspendedCount;
        private Money charged;
        private Money released;
        private Money interestSuspended;

        Tally(List<String> anomalies, long contaminated) {
            this.anomalies = java.util.Collections.synchronizedList(
                new ArrayList<>(anomalies));
            this.contaminated = contaminated;
        }

        synchronized void anomaly(String detail) {
            anomalies.add(detail);
        }

        synchronized void downgraded() {
            downgraded++;
        }

        synchronized void charged(Money amount) {
            charged = charged == null ? amount : charged.plus(amount);
        }

        synchronized void released(Money amount) {
            released = released == null ? amount : released.plus(amount);
        }

        synchronized void suspended(Money amount) {
            suspendedCount++;
            interestSuspended = interestSuspended == null ? amount
                                                          : interestSuspended.plus(amount);
        }

        synchronized Outcome toOutcome(long examined) {
            return new Outcome(examined, downgraded, contaminated, suspendedCount, charged,
                               released, interestSuspended, anomalies);
        }
    }
}
