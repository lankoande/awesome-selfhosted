package io.corebanking.loan.service;

import io.corebanking.kernel.concurrent.Parallel;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.DatedFlow;
import io.corebanking.loan.DisbursementPlan;
import io.corebanking.loan.DueCategory;
import io.corebanking.loan.EffectiveRate;
import io.corebanking.loan.InterimInterest;
import io.corebanking.loan.Instalment;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.loan.Teg;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deblocage echelonne : ouverture de la mobilisation, deblocage des tranches, interets
 * intercalaires, arrete de l'echeancier definitif.
 *
 * <h2>La regle qui structure tout : on ne paie que ce qu'on a recu</h2>
 *
 * <p>Pendant la mobilisation, l'encours vaut la somme des tranches deja versees. Les interets
 * courent sur cette somme, jour par jour, et sur rien d'autre. Le contournement habituel —
 * debloquer la totalite sur un compte d'attente puis « reverser » au fur et a mesure — fait payer
 * a l'emprunteur des fonds qu'il n'a pas recus, laisse une comptabilite parfaitement equilibree, et
 * ne se decouvre qu'a la reclamation.
 *
 * <h2>Trois moments, trois decisions differentes</h2>
 *
 * <ol>
 *   <li><b>A l'ouverture</b>, le plan est confronte aux conditions du credit et le cout previsionnel
 *       au plafond d'usure. C'est le seul moment ou le refus a un sens : aucun franc n'est sorti.</li>
 *   <li><b>A chaque tranche</b>, les fonds sortent. L'ordre des tranches, la date limite et le
 *       montant prevu sont controles ; au-dela, c'est le constat de la condition qui commande, et
 *       il est humain.</li>
 *   <li><b>A la cloture</b>, le capital tire est connu. L'echeancier definitif est publie et le
 *       taux effectif arrete sur les dates reelles de versement. S'il depasse le plafond, il est
 *       trop tard pour refuser : l'ecart est signale comme anomalie, et la ristourne de frais qui
 *       le corrige est une decision, pas un automatisme.</li>
 * </ol>
 *
 * <h2>Ce que ce service ne fait pas</h2>
 *
 * <p>Pas de commission d'engagement sur la fraction non tiree — elle se parametre comme une
 * commission ordinaire et releve du module des commissions, pas de celui-ci. Pas d'engagement hors
 * bilan : le montant accorde et non encore verse n'est porte par aucun compte, il se lit sur le
 * plan. La capitalisation des interets intercalaires dans le capital n'est pas couverte non plus,
 * et son absence est signalee plutot que contournee : elle produirait des interets sur des
 * interets, ce que le socle refuse par construction.
 */
public final class LoanMobilisationService {

    private static final int PARALLELISM = Parallel.defaultDegree("loan.mobilisation.parallelism");

    private final Database database;
    private final PostingService postingService;
    private final int parallelism;

    public LoanMobilisationService(Database database, PostingService postingService) {
        this(database, postingService, PARALLELISM);
    }

    public LoanMobilisationService(Database database, PostingService postingService,
                                   int parallelism) {
        this.database = database;
        this.postingService = postingService;
        this.parallelism = Math.max(1, parallelism);
    }

    // ------------------------------------------------------------------ ouverture

    /**
     * Ouvre la mobilisation : enregistre le plan, la duree accordee et les conditions.
     *
     * <p>Le <b>cout previsionnel</b> est confronte au plafond d'usure ici, sur l'hypothese que
     * toutes les tranches seront tirees comme prevu. C'est le seul moment ou le refus protege
     * l'emprunteur : apres le premier versement, le depassement ne se corrige plus.
     *
     * @param upfrontFees frais de dossier, retenus sur la premiere tranche
     */
    public void open(UUID contractId, DisbursementPlan plan, LoanTerms terms, Money upfrontFees,
                     UUID actorId, UUID approverId) {
        database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            if (contract.status() != LoanContract.Status.DRAFT) {
                throw new IllegalStateException(
                    "Credit " + contract.reference() + " deja " + contract.status()
                    + " : une mobilisation s'ouvre sur un contrat non encore debloque.");
            }
            if (!terms.principal().equals(contract.principal())) {
                throw new IllegalArgumentException(
                    "Conditions portant sur " + terms.principal() + " pour un credit de "
                    + contract.principal() + ".");
            }
            plan.requireConsistentWith(terms);
            Money fees = upfrontFees == null ? Money.zero(contract.currency()) : upfrontFees;

            ProductVersion product = product(c, contract, terms.disbursedOn());
            Teg previsionnel = projectedTeg(plan, terms, fees, product);
            LoanCatalog.usuryRate(product).ifPresent(ceiling -> {
                if (previsionnel.exceeds(ceiling)) {
                    throw new LoanService.UsuryCeilingExceededException(contract.reference(),
                                                                        previsionnel, ceiling);
                }
            });

            LoanStore.recordFinancialTerms(c, contractId, terms);
            LoanStore.activate(c, contractId, approverId);
            Tranches.openMobilisation(c, contractId, plan, terms, fees, actorId, approverId);
            return null;
        });
    }

    /**
     * Cout previsionnel du credit : toutes les tranches tirees a la date prevue, mobilisation close
     * a la date limite.
     *
     * <p>Le previsionnel est le seul chiffre disponible avant le premier versement, et c'est donc
     * celui sur lequel le plafond d'usure se controle. Il differera du definitif des qu'une tranche
     * sera versee en retard, pour moins, ou pas du tout — et c'est pour cela que le definitif est
     * arrete a la cloture plutot que reconduit.
     */
    private Teg projectedTeg(DisbursementPlan plan, LoanTerms terms, Money fees,
                             ProductVersion product) {
        List<InterimInterest.Drawing> prevus = plan.tranches().stream()
            .map(t -> new InterimInterest.Drawing(t.plannedOn(), t.amount())).toList();
        LocalDate cloture = plan.drawdownDeadline();
        AmortisationSchedule echeancier = ScheduleGenerator.generate(
            terms.forDrawn(plan.committed(), cloture.plusDays(1)));

        List<DatedFlow> recus = plan.tranches().stream()
            .map(t -> new DatedFlow(t.plannedOn(), t.amount())).toList();
        List<DatedFlow> payes = new ArrayList<>();
        if (fees.isPositive()) {
            payes.add(new DatedFlow(terms.disbursedOn(), fees));
        }
        LocalDate debut = terms.disbursedOn();
        for (LocalDate fin : InterimInterest.periodEnds(terms, cloture)) {
            payes.add(new DatedFlow(fin, interimTotal(prevus, terms, debut, fin)));
            debut = fin.plusDays(1);
        }
        if (!debut.isAfter(cloture)) {
            payes.add(new DatedFlow(cloture, interimTotal(prevus, terms, debut, cloture)));
        }
        for (Instalment instalment : echeancier.instalments()) {
            payes.add(new DatedFlow(instalment.dueDate(), instalment.total()));
        }
        return EffectiveRate.between(plan.tranches().get(0).plannedOn(), terms.frequency(), recus,
                                     payes, LoanCatalog.tegMethod(product));
    }

    private Money interimTotal(List<InterimInterest.Drawing> drawings, LoanTerms terms,
                               LocalDate from, LocalDate to) {
        Money interest = InterimInterest.accrue(drawings, from, to, terms.annualRatePercent(),
                                                terms.dayCount(), terms.currency()).interest();
        return interest.plus(tax(interest, terms));
    }

    private static Money tax(Money interest, LoanTerms terms) {
        return interest.times(terms.taxOnInterestRatePercent().movePointLeft(2)).roundToCurrency();
    }

    // ------------------------------------------------------------------ deblocage d'une tranche

    /**
     * Resultat d'un deblocage de tranche.
     *
     * @param shortfall   engagement tombe avec la tranche : le prevu moins le verse
     * @param drawn       capital mobilise apres ce deblocage
     * @param lastTranche vrai lorsque le plan est epuise. La mobilisation reste ouverte pour
     *                    autant : c'est le contrat qui fixe le debut de l'amortissement, pas le
     *                    rythme du chantier.
     */
    public record Release(int number, LocalDate on, Money amount, Money shortfall, Money fees,
                          Money drawn, boolean lastTranche) {}

    /**
     * Debloque une tranche a hauteur de l'avancement constate.
     *
     * <p>Le montant verse peut etre <b>inferieur</b> au montant prevu : c'est le cas normal d'un
     * avancement partiel. Le reliquat tombe avec la tranche et vient en diminution de l'engagement ;
     * le reporter sur une tranche ulterieure reviendrait a modifier le plan sans decision.
     *
     * <p>L'ordre des tranches est impose. Debloquer la troisieme avant la deuxieme voudrait dire que
     * la condition de la deuxieme n'a pas ete constatee, et le plan cesserait de dire quoi que ce
     * soit sur l'avancement du projet finance.
     */
    public Release release(UUID contractId, int number, Money amount, LocalDate on,
                           IdempotencyKey key, UUID actorId, UUID approverId) {
        return database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            Tranches.MobilisationRow mobilisation = requireOpenMobilisation(c, contract);
            List<Tranches.TrancheRow> plan = Tranches.of(c, contractId, contract.currency());
            Tranches.TrancheRow tranche = requireReleasable(plan, number, contract.reference());

            if (on.isBefore(contract.disbursedOn())) {
                throw new IllegalArgumentException(
                    "Deblocage au " + on + ", avant la prise d'effet du credit "
                    + contract.reference() + " du " + contract.disbursedOn() + ".");
            }
            if (on.isAfter(mobilisation.deadline())) {
                throw new DrawdownPeriodClosedException(contract.reference(), on,
                                                        mobilisation.deadline());
            }
            if (amount.isGreaterThan(tranche.plannedAmount())) {
                throw new IllegalArgumentException(
                    "Deblocage de " + amount + " sur une tranche de " + tranche.plannedAmount()
                    + " : verser plus que le prevu augmenterait l'engagement de la banque sans "
                    + "decision. Le plan se modifie, il ne se depasse pas.");
            }
            for (Tranches.TrancheRow earlier : plan) {
                if (earlier.status() == Tranches.Status.RELEASED
                    && earlier.releasedOn().isAfter(on)) {
                    throw new IllegalArgumentException(
                        "Deblocage au " + on + " apres une tranche deja versee le "
                        + earlier.releasedOn() + " : les mises a disposition ne remontent pas le "
                        + "temps, et l'assiette des interets intercalaires cesserait d'etre "
                        + "reconstituable.");
                }
            }

            // Les frais de dossier sont retenus sur la premiere mise a disposition, et sur elle
            // seule : les etaler sur les tranches en ferait dependre le montant du nombre de
            // tranches reellement tirees.
            boolean premiere = plan.stream().noneMatch(t -> t.status() == Tranches.Status.RELEASED);
            Money fees = premiere ? mobilisation.upfrontFees() : Money.zero(contract.currency());
            if (fees.isGreaterThanOrEqual(amount)) {
                throw new IllegalArgumentException(
                    "Frais de dossier de " + fees + " retenus sur une premiere tranche de " + amount
                    + " : l'emprunteur ne recevrait rien.");
            }

            ProductVersion product = product(c, contract, on);
            EvaluationContext input = EvaluationContext.builder()
                .put("amount", amount).put("upfront_fees", fees).build();
            UUID entryId = post(c, contract, product, LoanSchemas.trancheRelease(contract.currency()),
                                input, on, on, LoanSchemas.EVENT_TRANCHE_RELEASE, key, actorId,
                                null, false);
            Tranches.markReleased(c, tranche.id(), on, amount, entryId, actorId, approverId);

            Money shortfall = tranche.plannedAmount().minus(amount);
            boolean last = plan.stream()
                .noneMatch(t -> t.number() != number && t.status() == Tranches.Status.PLANNED);
            return new Release(number, on, amount, shortfall, fees,
                               Tranches.drawn(c, contractId, contract.currency()), last);
        });
    }

    /** Deblocage refuse parce que la periode de mobilisation est close. */
    public static class DrawdownPeriodClosedException extends RuntimeException {
        public DrawdownPeriodClosedException(String reference, LocalDate on, LocalDate deadline) {
            super("Credit " + reference + " : deblocage demande au " + on + " alors que la periode "
                  + "de mobilisation s'est achevee le " + deadline + ". L'echeancier definitif est "
                  + "arrete sur le capital tire a cette date ; y ajouter une tranche obligerait a "
                  + "le reconstruire et a reclamer deux fois les echeances deja rendues exigibles.");
        }
    }

    private static Tranches.TrancheRow requireReleasable(List<Tranches.TrancheRow> plan, int number,
                                                         String reference) {
        Tranches.TrancheRow found = plan.stream().filter(t -> t.number() == number).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Credit " + reference + " : aucune tranche de rang " + number + "."));
        if (found.status() != Tranches.Status.PLANNED) {
            throw new IllegalStateException(
                "Credit " + reference + " : tranche " + number + " deja " + found.status()
                + ". Les fonds ne se versent pas deux fois.");
        }
        for (Tranches.TrancheRow earlier : plan) {
            if (earlier.number() < number && earlier.status() == Tranches.Status.PLANNED) {
                throw new IllegalStateException(
                    "Credit " + reference + " : la tranche " + earlier.number() + " n'est pas "
                    + "debloquee. Debloquer la " + number + " avant elle reviendrait a dire que sa "
                    + "condition a ete constatee alors qu'elle ne l'a pas ete.");
            }
        }
        return found;
    }

    private Tranches.MobilisationRow requireOpenMobilisation(Connection c, LoanContract contract) {
        Tranches.MobilisationRow row = Tranches
            .mobilisationOf(c, contract.id(), contract.currency())
            .orElseThrow(() -> new IllegalStateException(
                "Credit " + contract.reference() + " : aucun plan de deblocage. Un credit verse en "
                + "une fois se debloque par LoanService.disburse."));
        if (!row.open()) {
            throw new IllegalStateException(
                "Credit " + contract.reference() + " : mobilisation close le " + row.closedOn()
                + ". L'echeancier definitif est publie et ne se reconstruit pas.");
        }
        return row;
    }

    // ------------------------------------------------------------------ traitement de fin de jour

    /**
     * Compte rendu d'une passe de mobilisation.
     *
     * <p>Une tranche non tiree a la date limite n'est pas une anomalie : c'est le fait de gestion
     * que l'etape est faite pour constater. Elle est comptee dans {@code commitmentCancelled}, et
     * l'etape ne s'arrete pas pour autant — un chantier qui n'a pas avance ne doit pas bloquer
     * l'arrete de la banque.
     *
     * @param commitmentCancelled engagement tombe faute d'avoir ete tire dans les delais
     */
    public record Outcome(long examined, long periodsBilled, Money interimInterest, long closed,
                          Money commitmentCancelled, List<String> anomalies) {

        public Outcome {
            anomalies = List.copyOf(anomalies == null ? List.of() : anomalies);
        }
    }

    /**
     * Facture les interets intercalaires echus et clot les mobilisations arrivees a terme.
     *
     * <p>L'etape precede l'exigibilite des echeances : c'est elle qui publie l'echeancier definitif,
     * et une echeance ne peut pas etre rendue exigible sur un plan qui n'existe pas encore.
     */
    public Outcome process(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                           UUID batchRunId) {
        List<UUID> contracts = database.inTransaction(
            c -> Tranches.openMobilisations(c, legalEntityId));

        Tally tally = new Tally();
        List<Runnable> tasks = new ArrayList<>(contracts.size());
        for (UUID contractId : contracts) {
            tasks.add(() -> {
                try {
                    processOne(contractId, businessDate, actorId, batchRunId, tally);
                } catch (RuntimeException e) {
                    tally.anomaly("credit " + contractId + " : " + e.getMessage());
                }
            });
        }
        Parallel.runAll(tasks, parallelism);
        return tally.toOutcome(contracts.size());
    }

    private void processOne(UUID contractId, LocalDate businessDate, UUID actorId, UUID batchRunId,
                            Tally tally) {
        database.inTransaction(c -> {
            LoanContract contract = LoanStore.requireContract(c, contractId);
            Tranches.MobilisationRow mobilisation = requireOpenMobilisation(c, contract);
            LoanTerms terms = contract.requireTerms().withTerm(
                mobilisation.instalmentCount(), mobilisation.graceInstalments(),
                mobilisation.firstDueDate());
            List<Tranches.TrancheRow> plan = Tranches.of(c, contractId, contract.currency());
            // La mobilisation se clot a sa date limite, et a elle seule. Avoir tire toutes ses
            // tranches en avance ne raccourcit pas la periode : c'est le contrat qui fixe le debut
            // de l'amortissement, et l'emprunteur qui detient les fonds en paie les interets
            // jusque-la. Clore des le dernier tirage avancerait la premiere periode
            // d'amortissement de plusieurs mois, sans que personne ne l'ait convenu.
            boolean closing = !businessDate.isBefore(mobilisation.deadline());

            LocalDate billed = bill(c, contract, terms, mobilisation, businessDate, closing, actorId,
                                    batchRunId, tally);
            if (billed != null) {
                Tranches.billedThrough(c, contractId, billed);
            }
            if (closing) {
                closeMobilisation(c, contract, terms, mobilisation, plan, businessDate,
                                  batchRunId, tally);
                tally.closed();
            }
            return null;
        });
    }

    /**
     * Facture les periodes intercalaires echues.
     *
     * <p>En regime courant, une periode par date d'echeance intercalaire. Le jour de la cloture, le
     * solde de la periode en cours est facture d'un bloc, quelle que soit sa duree : l'echeancier
     * definitif prend effet le lendemain, et aucun jour ne doit rester entre les deux.
     *
     * @return dernier jour couvert, ou {@code null} si rien n'a ete facture
     */
    private LocalDate bill(Connection c, LoanContract contract, LoanTerms terms,
                           Tranches.MobilisationRow mobilisation, LocalDate businessDate,
                           boolean closing, UUID actorId, UUID batchRunId, Tally tally) {
        List<InterimInterest.Drawing> drawings = Tranches.drawings(c, contract.id(),
                                                                   contract.currency());
        if (drawings.isEmpty()) {
            return null;                       // rien n'a ete verse : rien ne porte interet
        }
        List<LocalDate> ends = new ArrayList<>(
            InterimInterest.periodEnds(terms, businessDate).stream()
                .filter(end -> end.isAfter(mobilisation.billedThrough())).toList());
        if (closing && (ends.isEmpty() || !ends.get(ends.size() - 1).equals(businessDate))) {
            ends.add(businessDate);
        }

        LocalDate from = mobilisation.billedThrough().plusDays(1);
        LocalDate covered = null;
        for (LocalDate end : ends) {
            if (end.isBefore(from)) {
                continue;
            }
            InterimInterest.Accrual accrual = InterimInterest.accrue(
                drawings, from, end, terms.annualRatePercent(), terms.dayCount(),
                contract.currency());
            if (accrual.interest().isPositive()) {
                postInterim(c, contract, terms, accrual, actorId, batchRunId);
                tally.billed(accrual.interest());
            }
            covered = end;
            from = end.plusDays(1);
        }
        return covered;
    }

    private void postInterim(Connection c, LoanContract contract, LoanTerms terms,
                             InterimInterest.Accrual accrual, UUID actorId, UUID batchRunId) {
        ProductVersion product = product(c, contract, accrual.toInclusive());
        Money taxe = tax(accrual.interest(), terms);
        boolean suspended = LoanStore.isSuspended(c, contract.id());

        EvaluationContext input = EvaluationContext.builder()
            .put("interest", accrual.interest()).put("tax", taxe).build();
        UUID entryId = post(c, contract, product, LoanSchemas.interimInterest(contract.currency()),
                            input, accrual.toInclusive(), accrual.toInclusive(),
                            LoanSchemas.EVENT_INTERIM_INTEREST,
                            IdempotencyKey.forBatch(String.valueOf(batchRunId), "LOAN_INTERIM",
                                                     contract.id(), accrual.toInclusive()),
                            actorId, batchRunId, suspended);

        // La creance porte l'interet et sa taxe : c'est ce que le client doit, et c'est sur elle
        // que le prelevement de la journee s'exercera.
        UUID receivableId = LoanStore.addReceivable(
            c, contract.id(), null, 0, DueCategory.INTEREST, accrual.toInclusive(),
            accrual.interest().plus(taxe), batchRunId);
        Tranches.recordInterim(c, contract.id(), accrual, taxe, receivableId, entryId, batchRunId);
    }

    /**
     * Arrete la mobilisation : le reliquat non tire tombe, l'echeancier definitif est publie et le
     * taux effectif fige sur les dates reelles de versement.
     */
    private void closeMobilisation(Connection c, LoanContract contract, LoanTerms terms,
                                   Tranches.MobilisationRow mobilisation,
                                   List<Tranches.TrancheRow> plan, LocalDate on, UUID batchRunId,
                                   Tally tally) {
        for (Tranches.TrancheRow tranche : plan) {
            if (tranche.status() == Tranches.Status.PLANNED) {
                Tranches.cancel(c, tranche.id(), on, "Date limite de mobilisation atteinte",
                                batchRunId);
                tally.cancelled(tranche.plannedAmount());
            }
        }
        Money drawn = Tranches.drawn(c, contract.id(), contract.currency());
        if (!drawn.isPositive()) {
            // Aucune tranche versee : le credit n'a jamais existe economiquement. Le cloturer vaut
            // mieux que de publier un echeancier sur un capital nul, qui ferait vivre un contrat
            // vide dans tous les etats de portefeuille.
            Tranches.close(c, contract.id(), on, batchRunId);
            LoanStore.close(c, contract.id());
            return;
        }
        if (!on.plusDays(1).isBefore(mobilisation.firstDueDate())) {
            throw new IllegalStateException(
                "Cloture de la mobilisation du credit " + contract.reference() + " au " + on
                + " alors que la premiere echeance tombe le " + mobilisation.firstDueDate()
                + " : il ne resterait aucun jour a la premiere periode d'amortissement. Le "
                + "traitement de fin de journee a pris du retard sur la date limite du "
                + mobilisation.deadline() + ".");
        }

        LoanTerms definitives = terms.forDrawn(drawn, on.plusDays(1));
        AmortisationSchedule schedule = ScheduleGenerator.generate(definitives);
        // L'echeancier definitif n'est pas une decision nouvelle : c'est la consequence mecanique
        // du plan deja valide. Il porte donc les deux mains de l'ouverture de la mobilisation, et
        // non celle du traitement de nuit, qui n'a rien approuve.
        LoanStore.publishSchedule(c, contract.id(), schedule, LoanStore.ScheduleReason.MOBILISATION,
                                  on.plusDays(1), mobilisation.openedBy(),
                                  mobilisation.approvedBy(), batchRunId);

        ProductVersion product = product(c, contract, on);
        Teg teg = definitiveTeg(c, contract, definitives, mobilisation, schedule, product);
        LoanStore.recordDisbursementTerms(c, contract.id(), definitives,
                                          mobilisation.upfrontFees(), teg);
        LoanCatalog.usuryRate(product).ifPresent(ceiling -> {
            if (teg.exceeds(ceiling)) {
                // Les fonds sont verses : le refus n'a plus de prise. Un tirage partiel laisse les
                // frais de dossier peser sur un capital plus faible, et le taux effectif monte sans
                // que personne ne l'ait decide. C'est une anomalie bloquante et non un fait de
                // gestion : la banque est en infraction tant que la ristourne qui la corrige n'a
                // pas ete decidee, et l'arrete ne doit pas passer dessus en silence.
                tally.anomaly("credit " + contract.reference() + " : taux effectif de " + teg
                              + " au-dela du plafond d'usure de " + ceiling + " % apres tirage "
                              + "partiel — une ristourne de frais est a decider");
            }
        });
        Tranches.close(c, contract.id(), on, batchRunId);
    }

    private Teg definitiveTeg(Connection c, LoanContract contract, LoanTerms definitives,
                              Tranches.MobilisationRow mobilisation, AmortisationSchedule schedule,
                              ProductVersion product) {
        List<InterimInterest.Drawing> drawings = Tranches.drawings(c, contract.id(),
                                                                   contract.currency());
        List<DatedFlow> recus = drawings.stream()
            .map(d -> new DatedFlow(d.on(), d.amount())).toList();
        List<DatedFlow> payes = new ArrayList<>();
        if (mobilisation.upfrontFees().isPositive()) {
            payes.add(new DatedFlow(drawings.get(0).on(), mobilisation.upfrontFees()));
        }
        for (Tranches.InterimPeriod period : Tranches.interimPeriods(c, contract.id(),
                                                                      contract.currency())) {
            payes.add(new DatedFlow(period.periodEnd(), period.interest().plus(period.tax())));
        }
        for (Instalment instalment : schedule.instalments()) {
            payes.add(new DatedFlow(instalment.dueDate(), instalment.total()));
        }
        return EffectiveRate.between(drawings.get(0).on(), definitives.frequency(), recus, payes,
                                     LoanCatalog.tegMethod(product));
    }

    /** Compteurs d'une passe, alimentes depuis plusieurs fils. */
    private static final class Tally {
        private final List<String> anomalies = new ArrayList<>();
        private long periods;
        private long closed;
        private Money interest;
        private Money cancelled;

        synchronized void billed(Money amount) {
            periods++;
            interest = interest == null ? amount : interest.plus(amount);
        }

        synchronized void closed() {
            closed++;
        }

        synchronized void cancelled(Money amount) {
            cancelled = cancelled == null ? amount : cancelled.plus(amount);
        }

        synchronized void anomaly(String detail) {
            anomalies.add(detail);
        }

        synchronized Outcome toOutcome(long examined) {
            return new Outcome(examined, periods, interest, closed, cancelled, anomalies);
        }
    }

    // ------------------------------------------------------------------ interne

    private ProductVersion product(Connection c, LoanContract contract, LocalDate date) {
        return ProductCatalog.resolveAt(c, contract.legalEntityId(), contract.productCode(), date);
    }

    private UUID post(Connection c, LoanContract contract, ProductVersion product,
                      EventTemplate template, EvaluationContext input, LocalDate bookingDate,
                      LocalDate valueDate, String transactionType, IdempotencyKey key, UUID actorId,
                      UUID batchRunId, boolean suspended) {
        List<PostingLine> lines = SchemaEngine.linesFor(
            template, input, resolver(contract, product, suspended), contract.currency(), valueDate);
        PostingCommand command = batchRunId == null
            ? PostingCommand.online(key, contract.legalEntityId(), bookingDate, transactionType,
                                    actorId, lines)
            : PostingCommand.batch(key, contract.legalEntityId(), bookingDate, transactionType,
                                   actorId, batchRunId, lines);
        return postingService.post(command).entryId();
    }

    private AccountResolver resolver(LoanContract contract, ProductVersion product,
                                     boolean suspended) {
        return reference -> switch (reference.kind()) {
            case CONTRACT -> contract.loanAccountId();
            case PARAMETER -> switch (reference.value()) {
                case LoanSchemas.ROLE_SETTLEMENT -> contract.settlementAccountId();
                case LoanSchemas.ROLE_ACCRUED -> LoanCatalog.accruedReceivable(product);
                case LoanSchemas.ROLE_INTEREST_INCOME -> suspended
                    ? LoanCatalog.reservedInterest(product) : LoanCatalog.interestIncome(product);
                case LoanSchemas.ROLE_FEE_INCOME -> LoanCatalog.feeIncome(product);
                case LoanSchemas.ROLE_TAX -> LoanCatalog.taxAccount(product);
                default -> throw new AccountResolver.UnresolvableAccountException(reference,
                    "role inconnu du parametrage du produit " + product.code());
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "seuls le compte de pret et les comptes parametres sont resolvables ici");
        };
    }
}
