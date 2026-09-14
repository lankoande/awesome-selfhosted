package io.corebanking.fee.service;

import io.corebanking.fee.FeeAssessment;
import io.corebanking.fee.FeeCalculator;
import io.corebanking.fee.FeeTerms;
import io.corebanking.fee.InsufficientFundsPolicy;
import io.corebanking.fee.Proration;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.SchedulePeriod;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Perception des commissions echues.
 *
 * <h2>Ce que le service garantit</h2>
 *
 * <ul>
 *   <li><b>Une periode n'est facturee qu'une fois</b>, quel que soit le nombre de reprises du
 *       traitement. Deux verrous independants : la cle d'idempotence de l'ecriture est derivee du
 *       contenu — compte, commission, fin de periode — et non du run, donc une reprise sous un
 *       nouvel identifiant de traitement retombe sur la meme cle ; et la contrainte d'exclusion du
 *       registre interdit deux liquidations couvrant un meme jour. Le premier protege le journal,
 *       le second le registre ; ni l'un ni l'autre ne suffit seul.</li>
 *   <li><b>Une journee non traitee est rattrapee</b> avec le tarif de l'epoque. Les periodes
 *       echues depuis la derniere liquidation sont facturees chacune aux conditions en vigueur a
 *       sa propre date de perception, et non a celles du jour du rattrapage.</li>
 *   <li><b>Une commission non percue laisse une trace.</b> Exoneration, provision insuffisante,
 *       prorata nul : la liquidation est enregistree avec son montant et son motif. Le manque a
 *       gagner est ainsi mesurable, ce qu'il n'est jamais lorsque l'on se contente de ne rien
 *       comptabiliser.</li>
 * </ul>
 *
 * <h2>L'ordre de traitement, qui n'est pas indifferent</h2>
 *
 * <p>Les impayes sont representes <b>avant</b> les commissions de la journee. Sur un compte dont
 * la provision ne couvre qu'une des deux, c'est la dette la plus ancienne qui est soldee. L'ordre
 * inverse ferait vieillir indefiniment les creances les plus anciennes jusqu'a leur abandon, tout
 * en encaissant les plus recentes : la banque perdrait exactement ce qu'elle a le plus attendu.
 *
 * <h2>Ce que le service refuse de faire</h2>
 *
 * <p>Il n'encaisse jamais partiellement. Une commission dont la provision ne couvre que la moitie
 * est reportee en entier, pas coupee en deux. Deux raisons : la taxe suit la commission qu'elle
 * frappe, et un encaissement partiel obligerait a scinder une assiette taxable declaree ; et une
 * creance fractionnee en tranches successives produit un historique que plus personne ne sait
 * rapprocher de la facture d'origine.
 */
public final class FeeChargingService {

    /**
     * Nombre maximal de periodes rattrapees en un traitement pour un meme compte.
     *
     * <p>Un ancrage errone — une date de 1970 saisie a la place de 2026 — produirait sinon des
     * centaines de prelevements sur un compte client avant que quiconque ne s'en apercoive. La
     * borne transforme une faute de saisie en anomalie signalee.
     */
    private static final int MAX_CATCH_UP_PERIODS = 64;

    /**
     * Nombre de comptes traites de front lors de la perception.
     *
     * <p>Une commission debite un compte client different a chaque fois : contrairement aux
     * interets, elle ne s'agrege pas en une ecriture par couple de comptes generaux. Le cout est
     * donc d'une ecriture par compte, et la mesure montre qu'il domine le TFJ des lors qu'un
     * portefeuille entier est exigible le meme jour.
     *
     * <p>Les imputations etant independantes d'un compte a l'autre, elles se paralellisent. La
     * valeur ne doit pas depasser la taille du pool de connexions du ledger : au-dela, les taches
     * attendent une connexion au lieu de travailler. Le verrouillage ordonne du ledger garantit
     * l'absence d'interblocage, y compris sur les comptes generaux partages par toutes les
     * commissions.
     */
    private static final int PARALLELISM = Math.max(1, Integer.getInteger(
        "fee.parallelism", Math.min(8, Runtime.getRuntime().availableProcessors())));

    private final Database database;
    private final PostingService postingService;
    private final int parallelism;

    public FeeChargingService(Database database, PostingService postingService) {
        this(database, postingService, PARALLELISM);
    }

    public FeeChargingService(Database database, PostingService postingService, int parallelism) {
        this.database = database;
        this.postingService = postingService;
        this.parallelism = Math.max(1, parallelism);
    }

    /** Compte rendu d'une passe de perception. */
    public record Outcome(
        long accountsExamined,
        long collected,
        long forced,
        long waived,
        long deferred,
        long rejected,
        long recovered,
        long writtenOff,
        long notDue,
        Money collectedAmount,
        List<String> anomalies) {

        public Outcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }

        public long charged() {
            return collected + forced;
        }
    }

    /**
     * Percoit toutes les commissions echues a la date traitee pour un lot de comptes.
     *
     * @param businessDate journee comptable traitee. C'est elle qui decide de ce qui est echu, et
     *                     non la date du jour : un traitement de rattrapage produit ce que le
     *                     traitement du jour aurait produit.
     */
    public Outcome chargeDue(UUID legalEntityId, List<UUID> accountIds, LocalDate businessDate,
                             UUID actorId, UUID batchRunId) {
        if (accountIds.isEmpty()) {
            return new Outcome(0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
        }
        // Les schemas comptables sont dates et ne changent pas en cours de passe. Le cache est
        // vide a chaque appel plutot qu'entretenu pour la duree de vie du service : une version
        // de schema activee entre deux traitements doit etre vue par le suivant.
        templates.clear();

        Plan plan = database.inTransaction(
            c -> buildPlan(c, legalEntityId, accountIds, businessDate));

        Tally tally = new Tally(plan.anomalies());

        // Un compte a la fois, mais plusieurs comptes de front. Le decoupage par compte n'est pas
        // un detail de mise en oeuvre : impayes et commissions du jour puisent dans le meme
        // disponible, et doivent donc rester sequentiels entre eux.
        List<Runnable> tasks = new ArrayList<>();
        for (Map.Entry<UUID, AccountWork> entry : groupByAccount(plan).entrySet()) {
            AccountWork work = entry.getValue();
            tasks.add(() -> {
                for (FeeCharge arrear : work.arrears()) {
                    recover(arrear, plan, businessDate, actorId, batchRunId, tally);
                }
                for (Due due : work.due()) {
                    charge(due, plan, businessDate, actorId, batchRunId, tally);
                }
            });
        }
        runAll(tasks);
        return tally.toOutcome(accountIds.size(), plan.anyCurrency());
    }

    /** Impayes et commissions echues d'un meme compte, dans l'ordre ou ils doivent etre traites. */
    private record AccountWork(List<FeeCharge> arrears, List<Due> due) {}

    private Map<UUID, AccountWork> groupByAccount(Plan plan) {
        Map<UUID, AccountWork> work = new LinkedHashMap<>();
        // Les impayes d'abord : voir la note sur l'ordre de traitement.
        for (FeeCharge arrear : plan.arrears()) {
            work.computeIfAbsent(arrear.accountId(),
                                 id -> new AccountWork(new ArrayList<>(), new ArrayList<>()))
                .arrears().add(arrear);
        }
        for (Due due : plan.due()) {
            work.computeIfAbsent(due.accountId(),
                                 id -> new AccountWork(new ArrayList<>(), new ArrayList<>()))
                .due().add(due);
        }
        return work;
    }

    private void runAll(List<Runnable> tasks) {
        if (parallelism == 1 || tasks.size() <= 1) {
            tasks.forEach(Runnable::run);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, tasks.size()));
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            tasks.forEach(task -> futures.add(pool.submit(task)));
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    // La cause remonte telle quelle : le TFJ doit voir l'erreur d'origine, pas une
                    // exception d'ordonnancement qui masquerait le compte fautif.
                    if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("Perception des commissions", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Perception des commissions interrompue", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ recensement

    private record AccountFacts(UUID id, LocalDate openedAt, LocalDate closedAt,
                                CurrencyRef currency, String productCode) {}

    /** Commission echue, liquidee, en attente de denouement. */
    private record Due(UUID legalEntityId, UUID accountId, FeeTerms terms, String schemaCode,
                       FeeAssessment assessment, LocalDate chargeDate, int generation,
                       boolean exempt) {}

    private record Plan(List<Due> due, List<FeeCharge> arrears, Map<UUID, CurrencyRef> currencies,
                        Map<UUID, Money> available, Map<UUID, FeeTerms> arrearTerms,
                        List<String> anomalies) {

        CurrencyRef anyCurrency() {
            return currencies.values().stream().findFirst().orElse(null);
        }
    }

    private Plan buildPlan(Connection c, UUID legalEntityId, List<UUID> accountIds,
                           LocalDate businessDate) {
        List<String> anomalies = new ArrayList<>();
        Map<UUID, AccountFacts> facts = accountFacts(c, accountIds);
        Map<FeeLedger.Key, LocalDate> lastCharged = FeeLedger.lastChargedEnd(c, accountIds);
        Map<FeeLedger.Key, List<FeeLedger.Exemption>> exemptions =
            FeeLedger.exemptions(c, accountIds);
        Map<FeeLedger.Key, Integer> generations = FeeLedger.cancelledGenerations(c, accountIds);

        Map<String, ProductVersion> products = new LinkedHashMap<>();
        Map<String, FeeTerms> termsByProduct = new LinkedHashMap<>();
        Map<UUID, CurrencyRef> currencies = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();

        for (UUID accountId : accountIds) {
            AccountFacts account = facts.get(accountId);
            if (account == null || account.productCode() == null) {
                continue;
            }
            currencies.put(accountId, account.currency());
            ProductVersion product;
            try {
                product = products.computeIfAbsent(account.productCode(), code ->
                    ProductCatalog.resolveAt(c, legalEntityId, code, businessDate));
            } catch (RuntimeException e) {
                anomalies.add("compte " + accountId + " : " + e.getMessage());
                continue;
            }
            for (String feeCode : FeeCatalog.feeCodes(product)) {
                try {
                    collectCandidates(c, legalEntityId, account, product, feeCode, businessDate,
                                      lastCharged, exemptions, generations, termsByProduct,
                                      candidates, anomalies);
                } catch (RuntimeException e) {
                    anomalies.add("commission " + feeCode + " du compte " + accountId + " : "
                                  + e.getMessage());
                }
            }
        }

        List<FeeCharge> arrears = FeeLedger.arrears(c, accountIds, currencies);
        Map<UUID, FeeTerms> arrearTerms = termsOfArrears(arrears, facts, products, termsByProduct);

        Map<UUID, Money> available = availableBalances(c, concernedAccounts(candidates, arrears),
                                                       facts, products);
        List<Due> due = liquidate(candidates, c, currencies);
        return new Plan(due, arrears, currencies, available, arrearTerms, anomalies);
    }

    /** Periode echue en attente de liquidation : l'assiette n'est pas encore constatee. */
    private record Candidate(UUID legalEntityId, UUID accountId, FeeTerms terms, String schemaCode,
                             SchedulePeriod period, LocalDate chargeDate, int chargedDays,
                             boolean exempt, int generation, CurrencyRef currency) {}

    private void collectCandidates(Connection c, UUID legalEntityId, AccountFacts account,
                                   ProductVersion product, String feeCode, LocalDate businessDate,
                                   Map<FeeLedger.Key, LocalDate> lastCharged,
                                   Map<FeeLedger.Key, List<FeeLedger.Exemption>> exemptions,
                                   Map<FeeLedger.Key, Integer> generations,
                                   Map<String, FeeTerms> termsByProduct,
                                   List<Candidate> candidates, List<String> anomalies) {
        FeeTerms terms = resolveTerms(c, product, feeCode, account, termsByProduct);
        FeeLedger.Key key = new FeeLedger.Key(account.id(), feeCode);
        LocalDate lastEnd = lastCharged.get(key);

        // Un compte ouvert apres l'ancrage du produit ne doit pas se voir facturer les periodes
        // anterieures a son existence.
        LocalDate firstConcerned = lastEnd != null ? lastEnd.plusDays(1)
            : (account.openedAt().isAfter(terms.anchor()) ? account.openedAt() : terms.anchor());
        if (firstConcerned.isBefore(terms.anchor())) {
            firstConcerned = terms.anchor();
        }
        int index = terms.frequency().indexOfPeriodContaining(terms.anchor(), firstConcerned);

        int emitted = 0;
        while (emitted < MAX_CATCH_UP_PERIODS) {
            SchedulePeriod period = terms.period(index);
            LocalDate chargeDate = terms.chargeDate(period);
            if (chargeDate.isAfter(businessDate)) {
                break;
            }
            if (account.closedAt() != null && period.start().isAfter(account.closedAt())) {
                break;
            }
            int served = period.daysWithin(account.openedAt(), account.closedAt());
            int exemptDays = exemptDays(period, exemptions.get(key), account);
            boolean fullyExempt = served > 0 && exemptDays >= served;
            int chargedDays = terms.proration() == Proration.ACTUAL_DAYS
                ? Math.max(0, served - exemptDays)
                : served;

            int generation = generations.getOrDefault(
                FeeLedger.generationKey(account.id(), feeCode, period.end()), 0);
            candidates.add(new Candidate(legalEntityId, account.id(), terms,
                                         FeeCatalog.schemaCode(product, feeCode), period,
                                         chargeDate, fullyExempt ? served : chargedDays,
                                         fullyExempt, generation, account.currency()));
            emitted++;
            index++;
        }
        if (emitted == MAX_CATCH_UP_PERIODS) {
            anomalies.add("commission " + feeCode + " du compte " + account.id() + " : "
                          + MAX_CATCH_UP_PERIODS + " periodes echues en attente depuis "
                          + terms.anchor() + ". Verifier l'ancrage avant de poursuivre.");
        }
    }

    private FeeTerms resolveTerms(Connection c, ProductVersion product, String feeCode,
                                  AccountFacts account, Map<String, FeeTerms> cache) {
        String cacheKey = product.id() + "|" + feeCode;
        FeeTerms resolved = cache.get(cacheKey);
        if (resolved == null) {
            resolved = FeeCatalog.resolve(c, product, feeCode, account.currency(),
                                          account.openedAt());
            cache.put(cacheKey, resolved);
        }
        // L'ancrage au compte se rejoue sans relire le parametrage ; l'ancrage produit est commun.
        return FeeCatalog.hasExplicitAnchor(product, feeCode)
            ? resolved : resolved.withAnchor(account.openedAt());
    }

    private static int exemptDays(SchedulePeriod period, List<FeeLedger.Exemption> windows,
                                  AccountFacts account) {
        if (windows == null) {
            return 0;
        }
        int days = 0;
        for (FeeLedger.Exemption window : windows) {
            LocalDate from = window.from().isAfter(account.openedAt())
                ? window.from() : account.openedAt();
            LocalDate to = window.to();
            if (account.closedAt() != null && (to == null || to.isAfter(account.closedAt()))) {
                to = account.closedAt();
            }
            days += period.daysWithin(from, to);
        }
        return days;
    }

    // ------------------------------------------------------------------ liquidation

    private List<Due> liquidate(List<Candidate> candidates, Connection c,
                                Map<UUID, CurrencyRef> currencies) {
        Map<Candidate, Money> bases = constatedBases(c, candidates, currencies);
        List<Due> due = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            FeeAssessment assessment = FeeCalculator.assess(
                candidate.terms(), candidate.period(), bases.get(candidate),
                candidate.chargedDays());
            due.add(new Due(candidate.legalEntityId(), candidate.accountId(), candidate.terms(),
                            candidate.schemaCode(), assessment, candidate.chargeDate(),
                            candidate.generation(), candidate.exempt()));
        }
        return due;
    }

    /**
     * Constate les assiettes des commissions assises sur un solde.
     *
     * <p>Les lectures sont groupees : une commission mensuelle sur un portefeuille de deux millions
     * de comptes concerne quelques dizaines de milliers de comptes par jour, et les interroger un a
     * un couterait plus cher que tout le reste du traitement reuni.
     */
    private Map<Candidate, Money> constatedBases(Connection c, List<Candidate> candidates,
                                                 Map<UUID, CurrencyRef> currencies) {
        Map<Candidate, Money> bases = new LinkedHashMap<>();

        Map<LocalDate, List<Candidate>> closing = new LinkedHashMap<>();
        Map<SchedulePeriod, List<Candidate>> peaks = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            switch (candidate.terms().basis()) {
                case FLAT -> { }
                case RATE_ON_CLOSING_BALANCE, TIERED_ON_CLOSING_BALANCE ->
                    closing.computeIfAbsent(candidate.chargeDate(), d -> new ArrayList<>())
                        .add(candidate);
                case RATE_ON_HIGHEST_DEBIT_BALANCE ->
                    peaks.computeIfAbsent(candidate.period(), p -> new ArrayList<>())
                        .add(candidate);
            }
        }

        closing.forEach((date, group) -> {
            Map<UUID, java.math.BigDecimal> balances =
                signedBalancesAt(c, accountsOf(group), date);
            for (Candidate candidate : group) {
                java.math.BigDecimal signed = balances.getOrDefault(candidate.accountId(),
                                                                    java.math.BigDecimal.ZERO);
                // Un compte debiteur n'a pas d'assiette crediteur : elle est nulle, pas negative.
                bases.put(candidate, positive(signed, candidate.currency()));
            }
        });

        peaks.forEach((period, group) -> {
            Map<UUID, java.math.BigDecimal> deepest =
                deepestDebitOver(c, accountsOf(group), period);
            for (Candidate candidate : group) {
                java.math.BigDecimal peak = deepest.getOrDefault(candidate.accountId(),
                                                                 java.math.BigDecimal.ZERO);
                bases.put(candidate, positive(peak, candidate.currency()));
            }
        });
        return bases;
    }

    private static Money positive(java.math.BigDecimal value, CurrencyRef currency) {
        return value.signum() > 0 ? Money.of(value, currency) : Money.zero(currency);
    }

    // ------------------------------------------------------------------ denouement

    private void charge(Due due, Plan plan, LocalDate businessDate, UUID actorId, UUID batchRunId,
                        Tally tally) {
        CurrencyRef currency = plan.currencies().get(due.accountId());
        FeeCharge charge = new FeeCharge(
            null, due.legalEntityId(), due.accountId(), due.terms().code(),
            due.assessment().period(), due.chargeDate(), due.assessment().basisAmount(),
            due.assessment().gross(), due.assessment().net(), due.assessment().tax(),
            due.assessment().total(), due.terms().taxRatePercent(),
            due.assessment().chargedDays(), FeeOutcome.NOT_DUE, null, batchRunId, 1,
            due.generation(), null);

        if (due.exempt()) {
            // Le montant est conserve : c'est lui qui chiffre le cout du geste commercial.
            persist(charge, FeeOutcome.WAIVED, null, businessDate, tally);
            return;
        }
        if (due.assessment().isEmpty()) {
            persist(charge, FeeOutcome.NOT_DUE, null, businessDate, tally);
            return;
        }

        Money available = plan.available().getOrDefault(due.accountId(), Money.zero(currency));
        if (available.isLessThan(due.assessment().total())) {
            InsufficientFundsPolicy policy = due.terms().onInsufficientFunds();
            if (policy == InsufficientFundsPolicy.FORCE) {
                // Le prelevement passe et le compte devient debiteur. L'etat FORCED distingue ce
                // decouvert, cree par la banque, de celui que le client a lui-meme provoque.
                postAndPersist(charge, due.terms(), due.schemaCode(), currency, businessDate,
                               actorId, batchRunId, FeeOutcome.FORCED, tally, plan);
            } else {
                persist(charge,
                        policy == InsufficientFundsPolicy.DEFER
                            ? FeeOutcome.DEFERRED : FeeOutcome.REJECTED,
                        null, businessDate, tally);
            }
            return;
        }
        postAndPersist(charge, due.terms(), due.schemaCode(), currency, businessDate, actorId,
                       batchRunId, FeeOutcome.COLLECTED, tally, plan);
    }

    private void recover(FeeCharge arrear, Plan plan, LocalDate businessDate, UUID actorId,
                         UUID batchRunId, Tally tally) {
        // Les compteurs de recouvrement passent par le meme verrou que les autres.
        FeeTerms terms = plan.arrearTerms().get(arrear.id());
        if (terms == null) {
            tally.anomalies().add("commission reportee " + arrear.feeCode() + " du compte "
                                  + arrear.accountId() + " : parametrage introuvable, creance "
                                  + "laissee en l'etat.");
            return;
        }
        if (arrear.ageInDays(businessDate) > terms.arrearMaxAgeDays()) {
            database.inTransaction(c -> {
                FeeLedger.settle(c, arrear.settledAs(FeeOutcome.WRITTEN_OFF, null, businessDate));
                return null;
            });
            tally.countWrittenOff();
            return;
        }
        CurrencyRef currency = plan.currencies().get(arrear.accountId());
        Money available = plan.available().getOrDefault(arrear.accountId(), Money.zero(currency));
        if (available.isLessThan(arrear.total())) {
            return;                                     // toujours impayee, elle vieillit d'un jour
        }
        database.inTransaction(c -> {
            UUID entryId = post(arrear, terms, FeeSchemas.STANDARD_CODE, currency, businessDate,
                                actorId, batchRunId);
            FeeLedger.settle(c, arrear.settledAs(FeeOutcome.COLLECTED, entryId, businessDate));
            return null;
        });
        // Le disponible du compte est entame : les commissions du jour ne peuvent plus compter
        // dessus. Sans cette deduction, un meme franc solderait un impaye et une commission neuve.
        plan.available().put(arrear.accountId(), available.minus(arrear.total()));
        tally.countRecovered();
        tally.add(arrear.total());
    }

    private void persist(FeeCharge charge, FeeOutcome outcome, UUID entryId, LocalDate settledOn,
                         Tally tally) {
        FeeCharge finalCharge = new FeeCharge(
            charge.id(), charge.legalEntityId(), charge.accountId(), charge.feeCode(),
            charge.period(), charge.chargeDate(), charge.basisAmount(), charge.gross(),
            charge.net(), charge.tax(), charge.total(), charge.taxRatePercent(),
            charge.chargedDays(), outcome, entryId,
            charge.batchRunId(), 1, charge.generation(), outcome.isSettled() ? settledOn : null);
        database.inTransaction(c -> FeeLedger.record(c, finalCharge));
        tally.count(outcome, finalCharge.total());
    }

    /**
     * Impute et enregistre dans la meme transaction.
     *
     * <p>Les deux ecritures — celle du journal et celle du registre des commissions — sont
     * indissociables : une ecriture sans ligne de registre serait refacturee au traitement suivant,
     * une ligne de registre sans ecriture ferait disparaitre une commission facturee. Les reunir
     * evite en outre une acquisition de connexion et une validation par commission, ce qui est
     * mesurable des lors que tout un portefeuille est exigible le meme jour.
     */
    private void postAndPersist(FeeCharge charge, FeeTerms terms, String schemaCode,
                                CurrencyRef currency, LocalDate businessDate, UUID actorId,
                                UUID batchRunId, FeeOutcome outcome, Tally tally, Plan plan) {
        database.inTransaction(c -> {
            UUID entryId = post(charge, terms, schemaCode, currency, businessDate, actorId,
                                batchRunId);
            persist(charge, outcome, entryId, businessDate, tally);
            return null;
        });
        Money available = plan.available().get(charge.accountId());
        if (available != null) {
            plan.available().put(charge.accountId(), available.minus(charge.total()));
        }
    }

    private UUID post(FeeCharge charge, FeeTerms terms, String schemaCode, CurrencyRef currency,
                      LocalDate businessDate, UUID actorId, UUID batchRunId) {
        return database.inTransaction(c -> {
            EventTemplate template = template(c, charge.legalEntityId(), schemaCode, currency,
                                              charge.chargeDate());
            EvaluationContext input = EvaluationContext.builder()
                .put("net", charge.net())
                .put("tax", charge.tax())
                .build();
            List<PostingLine> lines = SchemaEngine.linesFor(
                template, input, resolver(charge, terms), currency, charge.chargeDate());

            // Cle derivee du contenu, jamais du run : une reprise sous un nouvel identifiant de
            // traitement retombe sur la meme cle et le journal refuse le doublon de lui-meme.
            IdempotencyKey key = IdempotencyKey.of(
                "FEE|" + charge.accountId() + "|" + charge.feeCode() + "|" + charge.period().end()
                + "|" + charge.generation());
            PostingResult result = postingService.post(PostingCommand.batch(
                key, charge.legalEntityId(), businessDate, "FEE_CHARGE", actorId, batchRunId,
                lines));
            return result.entryId();
        });
    }

    private AccountResolver resolver(FeeCharge charge, FeeTerms terms) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> charge.accountId();
            case PARAMETER -> switch (reference.value()) {
                case FeeSchemas.ROLE_INCOME -> terms.incomeAccount();
                case FeeSchemas.ROLE_TAX -> {
                    if (terms.taxAccount() == null) {
                        throw new AccountResolver.UnresolvableAccountException(reference,
                            "la commission " + terms.code() + " n'a pas de compte de taxe alors "
                            + "que son schema en impute une");
                    }
                    yield terms.taxAccount();
                }
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage de la commission " + terms.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "seuls le compte du contrat et les comptes parametres sont resolvables ici");
        };
    }

    private final Map<String, EventTemplate> templates = new ConcurrentHashMap<>();

    private EventTemplate template(Connection c, UUID legalEntityId, String schemaCode,
                                   CurrencyRef currency, LocalDate date) {
        if (FeeSchemas.STANDARD_CODE.equals(schemaCode)) {
            return templates.computeIfAbsent("std|" + currency.code(),
                                             key -> FeeSchemas.feeCharge(currency));
        }
        return templates.computeIfAbsent(schemaCode + "|" + date, key ->
            SchemaCatalog.resolveAt(c, legalEntityId, schemaCode, date)
                .requireTemplate(FeeSchemas.EVENT_FEE_CHARGE));
    }

    // ------------------------------------------------------------------ compteurs

    private static final class Tally {
        private final List<String> anomalies;
        private long collected;
        private long forced;
        private long waived;
        private long deferred;
        private long rejected;
        private long recovered;
        private long writtenOff;
        private long notDue;
        private Money amount;

        Tally(List<String> anomalies) {
            this.anomalies = java.util.Collections.synchronizedList(new ArrayList<>(anomalies));
        }

        List<String> anomalies() {
            return anomalies;
        }

        synchronized void count(FeeOutcome outcome, Money total) {
            switch (outcome) {
                case COLLECTED -> { collected++; add(total); }
                case FORCED    -> { forced++; add(total); }
                case WAIVED    -> waived++;
                case DEFERRED  -> deferred++;
                case REJECTED  -> rejected++;
                case WRITTEN_OFF -> writtenOff++;
                case NOT_DUE   -> notDue++;
                case CANCELLED -> { }
            }
        }

        synchronized void countRecovered() {
            recovered++;
        }

        synchronized void countWrittenOff() {
            writtenOff++;
        }

        synchronized void add(Money total) {
            amount = amount == null ? total : amount.plus(total);
        }

        synchronized Outcome toOutcome(long examined, CurrencyRef currency) {
            return new Outcome(examined, collected, forced, waived, deferred, rejected, recovered,
                               writtenOff, notDue,
                               amount != null ? amount
                                              : (currency == null ? null : Money.zero(currency)),
                               anomalies);
        }
    }

    // ------------------------------------------------------------------ lectures groupees

    private Map<UUID, AccountFacts> accountFacts(Connection c, Collection<UUID> accountIds) {
        Map<UUID, AccountFacts> facts = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.opened_at, a.closed_at, cur.code, cur.scale, cur.rounding_mode,"
            + "       ap.product_code"
            + "  FROM account a"
            + "  JOIN currency cur ON cur.code = a.currency"
            + "  LEFT JOIN LATERAL ("
            + "        SELECT product_code FROM account_product p"
            + "         WHERE p.account_id = a.id ORDER BY p.valid_from DESC LIMIT 1) ap ON TRUE"
            + " WHERE a.id = ANY (?)")) {
            ps.setArray(1, FeeLedger.uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    facts.put(id, new AccountFacts(
                        id, rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class),
                        new CurrencyRef(rs.getString(4), rs.getInt(5),
                                        java.math.RoundingMode.valueOf(rs.getString(6))),
                        rs.getString(7)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des comptes a facturer", e);
        }
        return facts;
    }

    private Map<UUID, java.math.BigDecimal> signedBalancesAt(Connection c,
                                                             Collection<UUID> accountIds,
                                                             LocalDate valueDate) {
        Map<UUID, java.math.BigDecimal> balances = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            return balances;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id,"
            + "       SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.account_id = ANY (?) AND l.value_date <= ?"
            + " GROUP BY l.account_id")) {
            ps.setArray(1, FeeLedger.uuidArray(c, accountIds));
            ps.setObject(2, valueDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Constat des soldes au " + valueDate, e);
        }
        return balances;
    }

    /**
     * Plus fort solde debiteur atteint sur la periode, par compte.
     *
     * <p>La serie est reconstituee en date de valeur : c'est la seule qui reflete les conditions de
     * banque. Un versement credite le jour meme mais value a J+2 laisse le compte debiteur deux
     * jours de plus, et c'est bien ce decouvert-la qui est commissionne.
     */
    private Map<UUID, java.math.BigDecimal> deepestDebitOver(Connection c,
                                                             Collection<UUID> accountIds,
                                                             SchedulePeriod period) {
        Map<UUID, java.math.BigDecimal> deepest = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            return deepest;
        }
        Map<UUID, java.math.BigDecimal> opening = signedBalancesAt(c, accountIds,
                                                                   period.start().minusDays(1));
        Map<UUID, Map<LocalDate, java.math.BigDecimal>> daily = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id, l.value_date,"
            + "       SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + " WHERE l.account_id = ANY (?) AND l.value_date BETWEEN ? AND ?"
            + " GROUP BY l.account_id, l.value_date")) {
            ps.setArray(1, FeeLedger.uuidArray(c, accountIds));
            ps.setObject(2, period.start());
            ps.setObject(3, period.end());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    daily.computeIfAbsent(rs.getObject(1, UUID.class), key -> new LinkedHashMap<>())
                        .put(rs.getObject(2, LocalDate.class), rs.getBigDecimal(3));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconstitution des soldes de la periode " + period, e);
        }

        for (UUID accountId : accountIds) {
            java.math.BigDecimal running =
                opening.getOrDefault(accountId, java.math.BigDecimal.ZERO);
            Map<LocalDate, java.math.BigDecimal> movements =
                daily.getOrDefault(accountId, Map.of());
            java.math.BigDecimal lowest = running;
            for (LocalDate day = period.start(); !day.isAfter(period.end()); day = day.plusDays(1)) {
                running = running.add(movements.getOrDefault(day, java.math.BigDecimal.ZERO));
                if (running.compareTo(lowest) < 0) {
                    lowest = running;
                }
            }
            deepest.put(accountId, lowest.negate());
        }
        return deepest;
    }

    private Map<UUID, Money> availableBalances(Connection c, Collection<UUID> accountIds,
                                               Map<UUID, AccountFacts> facts,
                                               Map<String, ProductVersion> products) {
        // Le disponible est entame par chaque perception, depuis plusieurs fils : chaque compte
        // n'est touche que par sa propre tache, mais la table elle-meme est partagee.
        Map<UUID, Money> available = new ConcurrentHashMap<>();
        if (accountIds.isEmpty()) {
            return available;
        }
        Map<UUID, java.math.BigDecimal> balances = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT b.account_id, COALESCE(SUM(b.balance), 0) FROM account_balance b"
            + " WHERE b.account_id = ANY (?) GROUP BY b.account_id")) {
            ps.setArray(1, FeeLedger.uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    balances.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du disponible des comptes a facturer", e);
        }

        for (UUID accountId : accountIds) {
            AccountFacts account = facts.get(accountId);
            if (account == null) {
                continue;
            }
            Money balance = Money.of(
                balances.getOrDefault(accountId, java.math.BigDecimal.ZERO), account.currency());
            ProductVersion product = products.get(account.productCode());
            Money limit = product == null ? Money.zero(account.currency())
                                          : FeeCatalog.overdraftLimit(product, account.currency());
            available.put(accountId, balance.plus(limit));
        }
        return available;
    }

    private static List<UUID> accountsOf(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::accountId).distinct().toList();
    }

    private static List<UUID> concernedAccounts(List<Candidate> candidates,
                                                List<FeeCharge> arrears) {
        List<UUID> accounts = new ArrayList<>(accountsOf(candidates));
        arrears.stream().map(FeeCharge::accountId).filter(id -> !accounts.contains(id))
            .forEach(accounts::add);
        return accounts;
    }

    private Map<UUID, FeeTerms> termsOfArrears(List<FeeCharge> arrears,
                                               Map<UUID, AccountFacts> facts,
                                               Map<String, ProductVersion> products,
                                               Map<String, FeeTerms> termsByProduct) {
        Map<UUID, FeeTerms> byCharge = new LinkedHashMap<>();
        for (FeeCharge arrear : arrears) {
            AccountFacts account = facts.get(arrear.accountId());
            if (account == null || account.productCode() == null) {
                continue;
            }
            ProductVersion product = products.get(account.productCode());
            if (product == null) {
                continue;
            }
            FeeTerms terms = termsByProduct.get(product.id() + "|" + arrear.feeCode());
            if (terms != null) {
                byCharge.put(arrear.id(), terms);
            }
        }
        return byCharge;
    }
}
