package io.corebanking.loan.service;

import io.corebanking.kernel.concurrent.Parallel;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AllocationOrder;
import io.corebanking.loan.Allocation;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.DueCategory;
import io.corebanking.loan.EffectiveRate;
import io.corebanking.loan.PaymentAllocator;
import io.corebanking.loan.Prepayment;
import io.corebanking.loan.PrepaymentMode;
import io.corebanking.loan.Prepayments;
import io.corebanking.loan.Receivable;
import io.corebanking.loan.Teg;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vie d'un credit : deblocage, exigibilite des echeances, recouvrement.
 *
 * <h2>Ce que le service garantit</h2>
 *
 * <ul>
 *   <li><b>Une echeance n'est rendue exigible qu'une fois.</b> Le drapeau porte par la ligne
 *       d'echeancier et la cle d'idempotence derivee du traitement se couvrent mutuellement : la
 *       reprise d'un TFJ ne reclame rien deux fois, et l'annulation d'un TFJ rend les echeances a
 *       nouveau exigibles au lieu de les perdre.</li>
 *   <li><b>L'imputation suit l'ordre du produit</b>, la creance la plus ancienne d'abord, et la
 *       ventilation est conservee ligne a ligne. C'est la seule piece a produire lorsqu'un client
 *       conteste l'imputation de son versement — le contentieux le plus frequent en credit.</li>
 *   <li><b>L'encours ne diminue qu'au reglement.</b> Rendre une echeance exigible ne cree aucun
 *       flux sur le capital : il est deja a l'actif depuis le deblocage.</li>
 * </ul>
 *
 * <h2>Ce que le service ne fait pas encore</h2>
 *
 * <p>Ni penalites de retard, ni interets de retard, ni classification, ni provisionnement. Les
 * categories de creance correspondantes existent et l'ordre d'imputation les traite deja ; ce qui
 * manque est le calcul qui les alimente. Le nombre de jours de retard, lui, est disponible
 * ({@link #daysPastDue}) : c'est l'entree de tout ce qui suivra.
 *
 * <p>Le prelevement automatique s'appuie sur le <b>disponible</b> du compte de reglement : solde
 * moins les blocages de montant en vigueur, plus l'autorisation de decouvert. Un blocage prime
 * sur le prelevement ; une autorisation le permet.
 */
public final class LoanService {

    /**
     * Nombre de credits traites de front.
     *
     * <p>L'exigibilite produit deux ecritures par contrat — la constatation des charges puis le
     * prelevement — sur un compte client different a chaque fois. Comme les commissions, elle ne
     * s'agrege pas, et c'est le nombre de contrats qui dimensionne la duree. Les contrats etant
     * independants les uns des autres, ils se traitent en parallele ; le verrouillage ordonne du
     * ledger rend l'absence d'interblocage structurelle, y compris sur les comptes generaux de
     * produit partages par tous les credits.
     */
    private static final int PARALLELISM = Parallel.defaultDegree("loan.parallelism");

    private final Database database;
    private final PostingService postingService;
    private final int parallelism;

    public LoanService(Database database, PostingService postingService) {
        this(database, postingService, PARALLELISM);
    }

    public LoanService(Database database, PostingService postingService, int parallelism) {
        this.database = database;
        this.postingService = postingService;
        this.parallelism = Math.max(1, parallelism);
    }

    // ------------------------------------------------------------------ deblocage

    /**
     * Debloque un credit : publie l'echeancier initial et met les fonds a disposition.
     *
     * <p>L'echeancier est publie <b>avant</b> l'imputation. Un deblocage suivi d'un echec de
     * generation laisserait des fonds verses sans plan de remboursement.
     */
    public UUID disburse(UUID contractId, AmortisationSchedule schedule, UUID actorId,
                         UUID approverId) {
        return disburse(contractId, schedule, null, actorId, approverId);
    }

    /**
     * Debloque un credit, frais de dossier retenus a la source.
     *
     * <p>Le <b>taux effectif global</b> est arrete ici, une fois pour toutes, et confronte au
     * plafond d'usure du produit. C'est le seul moment ou le controle a un sens : apres le
     * deblocage, les fonds sont verses et le depassement ne se corrige plus.
     *
     * <p>Le controle porte sur le taux effectif et non sur le taux nominal. Un credit affiche a
     * 12 % depasse un plafond a 15 % des lors qu'on lui prend 2 % de frais de dossier — et c'est
     * precisement le montage que le controle sur le taux nominal laisse passer.
     */
    public UUID disburse(UUID contractId, AmortisationSchedule schedule, Money upfrontFees,
                         UUID actorId, UUID approverId) {
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            requireConsistent(contract, schedule);
            ProductVersion product = product(c, contract, contract.disbursedOn());

            Money fees = upfrontFees == null ? Money.zero(contract.currency()) : upfrontFees;
            Teg teg = EffectiveRate.of(schedule, fees, LoanCatalog.tegMethod(product));
            LoanCatalog.usuryRate(product).ifPresent(ceiling -> {
                if (teg.exceeds(ceiling)) {
                    throw new UsuryCeilingExceededException(contract.reference(), teg, ceiling);
                }
            });

            UUID scheduleId = LoanStore.publishSchedule(
                c, contractId, schedule, LoanStore.ScheduleReason.INITIAL, contract.disbursedOn(),
                actorId, approverId);
            LoanStore.activate(c, contractId, approverId);
            LoanStore.recordDisbursementTerms(c, contractId, schedule.terms(), fees, teg);

            EvaluationContext input = EvaluationContext.builder()
                .put("principal", contract.principal())
                .put("upfront_fees", fees)
                .build();
            post(c, contract, product, LoanSchemas.disbursement(contract.currency()), input,
                 contract.disbursedOn(), contract.disbursedOn(), LoanSchemas.EVENT_DISBURSEMENT,
                 IdempotencyKey.of("LOANDISB|" + contractId), actorId, null);
            return scheduleId;
        });
    }

    /** Credit refuse au deblocage parce que son cout depasse le plafond legal. */
    public static class UsuryCeilingExceededException extends RuntimeException {
        public UsuryCeilingExceededException(String reference, Teg teg, java.math.BigDecimal ceiling) {
            super("Credit " + reference + " : taux effectif global de " + teg
                  + ", au-dela du plafond d'usure de " + ceiling + " %. Le deblocage est refuse : "
                  + "apres versement des fonds, le depassement ne se corrige plus.");
        }
    }

    /**
     * Publie une nouvelle version d'echeancier. L'ancienne est close, jamais effacee.
     *
     * <p>Le rechelonnement ne produit aucune ecriture : il change ce que le client devra, pas ce
     * qu'il doit deja. Les echeances deja rendues exigibles restent dues — les reprendre dans le
     * nouveau plan reviendrait a effacer des impayes constates.
     */
    public UUID reschedule(UUID contractId, AmortisationSchedule schedule,
                           LoanStore.ScheduleReason reason, LocalDate effectiveFrom, UUID actorId,
                           UUID approverId) {
        return database.inTransaction(c -> LoanStore.publishSchedule(
            c, contractId, schedule, reason, effectiveFrom, actorId, approverId));
    }

    // ------------------------------------------------------------------ remboursement anticipe

    /**
     * Rembourse par anticipation tout ou partie du capital restant du.
     *
     * <h2>Ce n'est pas un reglement d'echeance</h2>
     *
     * <p>Un remboursement anticipe vient en diminution du <b>capital non echu</b> et oblige a
     * reconstruire l'echeancier des echeances a venir. Le traiter comme un versement ordinaire
     * l'imputerait sur les creances echues et ne changerait rien au plan : le client paierait
     * d'avance sans rien economiser.
     *
     * <p>Les impayes doivent donc etre soldes <b>avant</b>. Le refus est explicite : laisser
     * rembourser du capital non echu alors que des echeances restent dues reviendrait a faire
     * courir des penalites sur un client qui vient de verser plusieurs mois d'avance.
     *
     * @param mode choix de l'emprunteur, pas de la banque : reduire la duree economise bien plus
     *             d'interets que reduire l'echeance
     */
    public Prepayment prepay(UUID contractId, Money amount, PrepaymentMode mode, LocalDate on,
                             IdempotencyKey key, UUID actorId, UUID approverId) {
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            ProductVersion product = product(c, contract, on);
            List<Receivable> open = LoanStore.openReceivables(c, contractId, contract.currency());
            if (!open.isEmpty()) {
                throw new ArrearsOutstandingException(contract.reference(), open.size());
            }

            Money outstanding = Balances.current(c, contract.loanAccountId());
            if (amount.isGreaterThan(outstanding)) {
                throw new IllegalArgumentException(
                    "Remboursement anticipe de " + amount + " sur un capital restant du de "
                    + outstanding + " : un versement excedentaire solde le credit, il ne le rend "
                    + "pas crediteur.");
            }
            Money remaining = outstanding.minus(amount);

            Money indemnity = Prepayment.indemnity(
                amount, LoanCatalog.prepaymentIndemnityRate(product),
                LoanCatalog.prepaymentCapPercent(product),
                LoanCatalog.prepaymentCapMonths(product), contract.requireTerms()
                    .annualRatePercent());

            EvaluationContext input = EvaluationContext.builder()
                .put("principal", amount).put("indemnity", indemnity).build();
            UUID entryId = post(c, contract, product, LoanSchemas.prepayment(contract.currency()),
                                input, on, on, LoanSchemas.EVENT_PREPAYMENT, key, actorId, null);
            LoanStore.recordPayment(c, contractId, on, amount.plus(indemnity), amount,
                                    "PREPAYMENT", entryId, null, key.value(), actorId);

            AmortisationSchedule rebuilt = null;
            if (remaining.isPositive()) {
                LoanStore.Remaining ahead = LoanStore.remainingAfter(c, contractId,
                                                                     contract.currency(), on)
                    .orElseThrow(() -> new IllegalStateException(
                        "Aucune echeance a venir sur le contrat " + contract.reference()
                        + " alors qu'il reste " + remaining + " a amortir."));
                rebuilt = Prepayments.rebuild(contract.requireTerms(), remaining, on,
                                              ahead.nextDueDate(), ahead.count(), mode,
                                              ahead.annuity()).orElseThrow();
                LoanStore.publishSchedule(c, contractId, rebuilt,
                                          LoanStore.ScheduleReason.EARLY_REPAYMENT,
                                          on.plusDays(1), actorId, approverId);
            } else {
                LoanStore.close(c, contractId, on, null);
            }
            return new Prepayment(on, amount, indemnity, mode, rebuilt);
        });
    }

    /** Remboursement anticipe refuse tant que des echeances restent dues. */
    public static class ArrearsOutstandingException extends RuntimeException {
        public ArrearsOutstandingException(String reference, int count) {
            super("Credit " + reference + " : " + count + " creance(s) echue(s) non reglee(s). Un "
                  + "remboursement anticipe porte sur le capital non echu ; solder les impayes "
                  + "d'abord, faute de quoi des penalites courraient sur un client qui vient de "
                  + "verser plusieurs mois d'avance.");
        }
    }

    // ------------------------------------------------------------------ exigibilite

    /** Compte rendu d'une passe d'exigibilite. */
    public record DueOutcome(
        long contractsExamined, long instalmentsMadeDue, long collected, Money collectedAmount,
        List<String> anomalies) {

        public DueOutcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    /**
     * Rend exigibles les echeances echues a la date traitee et tente le prelevement.
     *
     * <p>Les echeances d'un TFJ de rattrapage sont traitees dans l'ordre de leurs dates : une
     * echeance de septembre passee en octobre reste une creance de septembre, et c'est elle qui
     * compte les jours de retard.
     */
    public DueOutcome makeDue(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                              UUID batchRunId) {
        List<LoanContract> contracts = database.inTransaction(
            c -> LoanStore.activeContracts(c, legalEntityId));

        Tally tally = new Tally();
        List<Runnable> tasks = new ArrayList<>(contracts.size());
        for (LoanContract contract : contracts) {
            tasks.add(() -> {
                try {
                    tally.madeDue(makeDueFor(contract, businessDate, actorId, batchRunId));
                    // Le prelevement suit sans condition : un contrat sans echeance nouvelle peut
                    // porter un impaye ancien, et le lire coute moins qu'une requete de plus pour
                    // savoir s'il faut le lire.
                    tally.collected(collect(contract, businessDate, actorId, batchRunId));
                } catch (RuntimeException e) {
                    tally.anomaly("credit " + contract.reference() + " : " + e.getMessage());
                }
            });
        }
        Parallel.runAll(tasks, parallelism);
        return tally.toOutcome(contracts.size());
    }

    /** Compteurs d'une passe, alimentes depuis plusieurs fils. */
    private static final class Tally {
        private final List<String> anomalies = new ArrayList<>();
        private long madeDue;
        private long collected;
        private Money amount;

        synchronized void madeDue(long count) {
            madeDue += count;
        }

        synchronized void collected(Money taken) {
            if (taken != null && taken.isPositive()) {
                collected++;
                amount = amount == null ? taken : amount.plus(taken);
            }
        }

        synchronized void anomaly(String detail) {
            anomalies.add(detail);
        }

        synchronized DueOutcome toOutcome(long examined) {
            return new DueOutcome(examined, madeDue, collected, amount, anomalies);
        }
    }

    private long makeDueFor(LoanContract contract, LocalDate businessDate, UUID actorId,
                            UUID batchRunId) {
        List<LoanStore.DueLine> lines = database.inTransaction(
            c -> LoanStore.instalmentsDueOn(c, contract.id(), contract.currency(), businessDate));

        for (LoanStore.DueLine line : lines) {
            database.inTransaction(c -> {
                ProductVersion product = product(c, contract, line.dueDate());
                LoanStore.markDue(c, line.scheduleId(), line.number(), businessDate, batchRunId);

                addReceivable(c, contract, line, DueCategory.PRINCIPAL, line.principal(),
                              batchRunId);
                addReceivable(c, contract, line, DueCategory.INTEREST,
                              line.interest().plus(line.tax()), batchRunId);
                addReceivable(c, contract, line, DueCategory.FEES_AND_INSURANCE,
                              line.insurance().plus(line.fee()), batchRunId);

                if (line.charges().isPositive()) {
                    // La classification de la veille commande la constatation du jour : l'etape
                    // de classification tourne apres celle-ci, et lire « la derniere » donne donc
                    // celle de la journee precedente. C'est voulu — classer avant d'avoir constate
                    // les impayes du jour serait circulaire.
                    boolean suspended = LoanStore.isSuspended(c, contract.id());
                    EvaluationContext input = EvaluationContext.builder()
                        .put("interest", line.interest())
                        .put("insurance", line.insurance())
                        .put("fee", line.fee())
                        .put("tax", line.tax())
                        .build();
                    post(c, contract, product, LoanSchemas.instalmentDue(contract.currency()),
                         input, businessDate, line.dueDate(), LoanSchemas.EVENT_INSTALMENT_DUE,
                         IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_DUE",
                                                 line.scheduleId(), line.number()),
                         actorId, batchRunId, suspended);
                }
                return null;
            });
        }
        return lines.size();
    }

    private void addReceivable(Connection c, LoanContract contract, LoanStore.DueLine line,
                               DueCategory category, Money amount, UUID batchRunId) {
        if (amount.isPositive()) {
            LoanStore.addReceivable(c, contract.id(), line.scheduleId(), line.number(), category,
                                    line.dueDate(), amount, batchRunId);
        }
    }

    // ------------------------------------------------------------------ recouvrement

    /** Resultat d'un reglement. */
    public record Settlement(UUID paymentId, Money paid, Money allocated, Money unallocated,
                             List<Allocation> allocations) {}

    /**
     * Impute un reglement sur les creances du contrat.
     *
     * <p>Le montant est impute dans l'ordre du produit, la creance la plus ancienne d'abord, et
     * <b>partiellement si necessaire</b> : une echeance de credit est une dette qui s'amortit.
     * L'excedent, lui, n'est pas consomme en silence — il est restitue a l'appelant, a qui il
     * revient de decider s'il constitue un remboursement anticipe ou un avoir.
     */
    public Settlement settle(UUID contractId, Money amount, LocalDate valueDate, String source,
                             IdempotencyKey key, UUID actorId, UUID batchRunId) {
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            ProductVersion product = product(c, contract, valueDate);
            List<Receivable> receivables = LoanStore.openReceivables(c, contractId,
                                                                     contract.currency());
            AllocationOrder order = LoanCatalog.allocationOrder(product);
            PaymentAllocator.Result result = PaymentAllocator.allocate(amount, receivables, order);

            Money principal = sumOf(result, DueCategory.PRINCIPAL, contract.currency())
                .plus(sumOf(result, DueCategory.FUTURE_PRINCIPAL, contract.currency()));
            Money charges = result.allocated().minus(principal);

            UUID entryId = null;
            if (result.allocated().isPositive()) {
                EvaluationContext input = EvaluationContext.builder()
                    .put("principal", principal).put("charges", charges).build();
                entryId = post(c, contract, product, LoanSchemas.repayment(contract.currency()),
                               input, valueDate, valueDate, LoanSchemas.EVENT_REPAYMENT, key,
                               actorId, batchRunId);
            }

            UUID paymentId = LoanStore.recordPayment(c, contractId, valueDate, amount,
                                                     result.allocated(), source, entryId,
                                                     batchRunId, key.value(), actorId);
            for (Allocation allocation : result.allocations()) {
                LoanStore.reduceReceivable(c, allocation.receivable().id(), allocation.amount(),
                                           valueDate);
                LoanStore.recordAllocation(c, paymentId, allocation.receivable().id(),
                                           allocation.amount());
            }
            return new Settlement(paymentId, amount, result.allocated(), result.unallocated(),
                                  result.allocations());
        });
    }

    /**
     * Preleve d'office sur le compte de reglement, a hauteur du disponible.
     *
     * <p>Le prelevement partiel est admis : prendre ce qui est la reduit la dette et arrete le
     * vieillissement de la part payee. Renoncer parce que le compte ne couvre pas tout laisserait
     * courir les jours de retard sur un montant que le client a en partie provisionne.
     */
    private Money collect(LoanContract contract, LocalDate businessDate, UUID actorId,
                          UUID batchRunId) {
        return database.inTransaction(c -> {
            ProductVersion product = product(c, contract, businessDate);
            if (!LoanCatalog.directDebit(product)) {
                return null;
            }
            List<Receivable> receivables = LoanStore.openReceivables(c, contract.id(),
                                                                     contract.currency());
            if (receivables.isEmpty()) {
                return null;
            }
            Money owed = Money.zero(contract.currency());
            for (Receivable receivable : receivables) {
                owed = owed.plus(receivable.outstanding());
            }
            // Le disponible, pas le solde : un blocage de montant n'est pas de l'argent que le
            // client peut engager, et une autorisation de decouvert en est.
            Money available = Balances.available(c, contract.settlementAccountId(), businessDate);
            Money take = available.isLessThan(owed) ? available : owed;
            if (!take.isPositive()) {
                return null;
            }

            IdempotencyKey key = IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_DD",
                                                         contract.id(), businessDate);
            if (LoanStore.paymentExists(c, key.value())) {
                return null;                                     // reprise : deja preleve
            }
            return settle(contract.id(), take, businessDate, "DIRECT_DEBIT", key, actorId,
                          batchRunId).allocated();
        });
    }

    // ------------------------------------------------------------------ cloture

    /** Compte rendu d'une passe de cloture. */
    public record ClosureOutcome(long examined, long closed, List<String> anomalies) {
        public ClosureOutcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    /**
     * Clot les credits qui n'ont plus rien a reclamer.
     *
     * <h2>Pourquoi a l'arrete, et apres la classification</h2>
     *
     * <p>Un credit integralement rembourse restait actif : rien ne le cloturait, et chaque arrete
     * le reexaminait a vie. Le clore au moment du dernier reglement aurait ete plus simple, et
     * faux : la provision d'un credit douteux se reprend a la classification, sur un encours
     * devenu nul — un contrat clos avant elle sortirait du portefeuille classe avec sa provision
     * intacte, et elle ne serait jamais reprise. La cloture est donc un acte de l'arrete, prononce
     * une fois la classification passee.
     *
     * <h2>L'encours residuel est une anomalie, pas un cas a arrondir</h2>
     *
     * <p>Un capital restant du alors que toutes les echeances sont reclamees et reglees ne se
     * cloture pas : c'est un ecart entre le compte de pret et le sous-livre des echeances, et
     * l'arrete doit le nommer plutot que le faire disparaitre.
     */
    public ClosureOutcome closeSettled(UUID legalEntityId, LocalDate businessDate,
                                       UUID batchRunId) {
        List<LoanContract> candidates = database.inTransaction(
            c -> LoanStore.settledCandidates(c, legalEntityId));
        List<String> anomalies = new ArrayList<>();
        long closed = 0;
        for (LoanContract contract : candidates) {
            try {
                boolean done = database.inTransaction(c -> {
                    Money outstanding = Balances.current(c, contract.loanAccountId());
                    if (!outstanding.isZero()) {
                        anomalies.add("credit " + contract.reference() + " : encours residuel de "
                                      + outstanding + " sans echeance a venir ni creance ouverte "
                                      + "— ecart entre le compte de pret et le sous-livre");
                        return false;
                    }
                    LoanStore.close(c, contract.id(), businessDate, batchRunId);
                    return true;
                });
                if (done) {
                    closed++;
                }
            } catch (RuntimeException e) {
                anomalies.add("credit " + contract.reference() + " : " + e.getMessage());
            }
        }
        return new ClosureOutcome(candidates.size(), closed, anomalies);
    }

    // ------------------------------------------------------------------ retard

    /**
     * Nombre de jours de retard du credit : l'age de son impaye le plus ancien, delai de grace
     * deduit.
     *
     * <p>C'est <b>le</b> chiffre du credit. Il determine le declassement, le provisionnement, la
     * suspension des interets et la declaration a la centrale des risques. Il se compte sur la
     * creance la plus ancienne encore ouverte, et non sur la derniere echeance impayee : un client
     * qui paie ses echeances recentes en laissant courir une ancienne reste en retard de l'age de
     * l'ancienne.
     */
    public long daysPastDue(UUID contractId, LocalDate at) {
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            ProductVersion product = product(c, contract, at);
            return LoanStore.oldestUnpaid(c, contractId)
                .map(oldest -> Math.max(0,
                    ChronoUnit.DAYS.between(oldest, at) - LoanCatalog.graceDays(product)))
                .orElse(0L);
        });
    }

    // ------------------------------------------------------------------ interne

    private static Money sumOf(PaymentAllocator.Result result, DueCategory category,
                               CurrencyRef currency) {
        Money total = Money.zero(currency);
        for (Allocation allocation : result.allocations()) {
            if (allocation.receivable().category() == category) {
                total = total.plus(allocation.amount());
            }
        }
        return total;
    }

    private ProductVersion product(Connection c, LoanContract contract, LocalDate date) {
        return ProductCatalog.resolveAt(c, contract.legalEntityId(), contract.productCode(), date);
    }

    private UUID post(Connection c, LoanContract contract, ProductVersion product,
                      EventTemplate template, EvaluationContext input, LocalDate bookingDate,
                      LocalDate valueDate, String transactionType, IdempotencyKey key, UUID actorId,
                      UUID batchRunId) {
        return post(c, contract, product, template, input, bookingDate, valueDate, transactionType,
                    key, actorId, batchRunId, false);
    }

    private UUID post(Connection c, LoanContract contract, ProductVersion product,
                      EventTemplate template, EvaluationContext input, LocalDate bookingDate,
                      LocalDate valueDate, String transactionType, IdempotencyKey key, UUID actorId,
                      UUID batchRunId, boolean suspended) {
        List<PostingLine> lines = SchemaEngine.linesFor(
            template, input, resolver(contract, product, suspended), contract.currency(),
            valueDate);
        PostingCommand command = batchRunId == null
            ? PostingCommand.online(key, contract.legalEntityId(), bookingDate, transactionType,
                                    actorId, lines)
            : PostingCommand.batch(key, contract.legalEntityId(), bookingDate, transactionType,
                                   actorId, batchRunId, lines);
        PostingResult result = postingService.post(command);
        return result.entryId();
    }

    /**
     * @param suspended vrai lorsque le credit est classe au-dela du seuil de suspension : les
     *                  interets naissent alors directement en interets reserves, hors resultat.
     *                  L'assurance et les frais, eux, restent en produits — la suspension est une
     *                  regle sur les interets, pas sur les accessoires.
     */
    private AccountResolver resolver(LoanContract contract, ProductVersion product,
                                     boolean suspended) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> contract.loanAccountId();
            case PARAMETER -> switch (reference.value()) {
                case LoanSchemas.ROLE_SETTLEMENT -> contract.settlementAccountId();
                case LoanSchemas.ROLE_ACCRUED -> LoanCatalog.accruedReceivable(product);
                case LoanSchemas.ROLE_ACCRUED_INTEREST -> LoanCatalog.accruedInterest(product);
                case LoanSchemas.ROLE_INTEREST_INCOME -> suspended
                    ? LoanCatalog.reservedInterest(product) : LoanCatalog.interestIncome(product);
                case LoanSchemas.ROLE_INSURANCE_INCOME -> LoanCatalog.insuranceIncome(product);
                case LoanSchemas.ROLE_FEE_INCOME -> LoanCatalog.feeIncome(product);
                case LoanSchemas.ROLE_TAX -> LoanCatalog.taxAccount(product);
                case LoanSchemas.ROLE_PREPAYMENT_INDEMNITY ->
                    LoanCatalog.prepaymentIndemnity(product);
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage du produit " + product.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "seuls le compte de pret et les comptes parametres sont resolvables ici");
        };
    }

    private static void requireConsistent(LoanContract contract, AmortisationSchedule schedule) {
        if (!schedule.terms().principal().equals(contract.principal())) {
            throw new LedgerStoreException(
                "L'echeancier porte sur " + schedule.terms().principal() + " alors que le contrat "
                + contract.reference() + " porte sur " + contract.principal() + ".");
        }
        if (!schedule.terms().disbursedOn().equals(contract.disbursedOn())) {
            throw new LedgerStoreException(
                "L'echeancier part du " + schedule.terms().disbursedOn() + " alors que le contrat "
                + contract.reference() + " est debloque le " + contract.disbursedOn() + ".");
        }
    }
}
