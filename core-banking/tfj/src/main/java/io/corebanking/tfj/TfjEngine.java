package io.corebanking.tfj;

import io.corebanking.deposits.Dormancy;
import io.corebanking.deposits.Holds;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.interest.service.InterestPositions;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.service.LoanStore;
import io.corebanking.party.KycReviews;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration du traitement de fin de journee.
 *
 * <h2>Ce que le moteur garantit</h2>
 *
 * <ul>
 *   <li><b>Unicite.</b> Un seul TFJ reel par entite et par date, garanti par un index unique et non
 *       par une precaution applicative : deux instances lancees en meme temps ne peuvent pas
 *       produire deux arretes.</li>
 *   <li><b>Ordre des journees.</b> Le TFJ ne traite que la date comptable courante de l'entite.
 *       Sauter une journee est donc impossible, et le rattrapage consiste a enchainer les TFJ dans
 *       l'ordre, chacun avec sa date et son parametrage d'epoque. Il n'existe pas de « fusion » de
 *       plusieurs journees : elle produirait des interets faux.</li>
 *   <li><b>Reprise.</b> Un echec en etape sept se reprend a l'etape sept. Les etapes franchies ne
 *       sont pas rejouees, et le seraient-elles qu'elles ne produiraient aucun doublon.</li>
 *   <li><b>Annulation.</b> Toutes les ecritures d'un TFJ portent son identifiant, ce qui permet de
 *       contre-passer l'integralite d'un arrete. C'est la fonction qui distingue un TFJ industriel
 *       d'un script : sans elle, une erreur de parametrage decouverte apres l'arrete se corrige
 *       compte par compte.</li>
 * </ul>
 *
 * <h2>Ce que le moteur dit de lui-meme</h2>
 *
 * <p>Chaque frontiere — lancement, reprise, etape, fin, annulation — est journalisee avec ce
 * qu'il faut pour diagnostiquer une nuit sans ouvrir la base : entite, journee, identifiant du
 * traitement, etape, volumes lus et ecrits, duree, anomalies. Le rapport en base
 * ({@code batch_step}) reste la reference ; le journal est ce que l'astreinte lit en premier.
 */
public final class TfjEngine {

    private static final Logger LOG = LoggerFactory.getLogger(TfjEngine.class);

    private final Database database;
    private final PostingService postingService;
    private final List<TfjStep> steps;
    private final RunType runType;

    public TfjEngine(Database database, PostingService postingService, List<TfjStep> steps) {
        this(database, postingService, steps, RunType.TFJ);
    }

    /**
     * @param runType nature du traitement. Le traitement de fin de mois partage le moteur — etapes,
     *                reprise, annulation — mais ne touche pas a la date comptable : il porte sur
     *                un mois deja arrete jour par jour, et clot sa periode.
     */
    public TfjEngine(Database database, PostingService postingService, List<TfjStep> steps,
                     RunType runType) {
        this.database = database;
        this.postingService = postingService;
        this.steps = List.copyOf(steps);
        this.runType = runType;
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Un traitement sans etape ne signifie rien");
        }
    }

    public RunType runType() {
        return runType;
    }

    // ------------------------------------------------------------------ lancement

    public TfjRun run(UUID legalEntityId, LocalDate businessDate, UUID actorId, RunMode mode) {
        // Le traitement entier travaille dans l'entite qu'il arrete : c'est la portee que chacune
        // de ses transactions transmet a la base, et que la Row Level Security applique.
        try (Database.EntityScope scope = Database.enterEntity(legalEntityId)) {
            return runWithin(legalEntityId, businessDate, actorId, mode);
        }
    }

    private TfjRun runWithin(UUID legalEntityId, LocalDate businessDate, UUID actorId,
                             RunMode mode) {
        // L'etat d'un traitement existant est examine AVANT la date comptable courante. Demander
        // le TFJ d'une journee deja arretee doit renvoyer son rapport — c'est la reponse utile a
        // la question posee — et non une erreur sur la date, qui serait exacte et inexploitable.
        if (mode == RunMode.REAL) {
            Optional<TfjRun> existing = findRealRun(legalEntityId, businessDate);
            if (existing.isPresent()) {
                TfjRun run = existing.get();
                return switch (run.status()) {
                    case COMPLETED -> run;
                    case FAILED -> throw new TfjRefusedException(
                        "Le TFJ du " + businessDate + " est en echec a l'etape « "
                        + run.failedStep().map(TfjRun.StepExecution::name).orElse("?")
                        + " ». Le reprendre, plutot que d'en lancer un second.");
                    case RUNNING -> throw new TfjRefusedException(
                        "Un TFJ du " + businessDate + " est deja en cours.");
                    case CANCELLED -> throw new IllegalStateException("etat filtre en amont");
                };
            }
        }

        if (runType == RunType.TFJ) {
            LocalDate current = currentBusinessDate(legalEntityId);
            if (!businessDate.equals(current)) {
                throw new TfjRefusedException(
                    "Le TFJ ne traite que la date comptable courante de l'entite, soit le "
                    + current + ". Demande pour le " + businessDate + ". Un rattrapage s'effectue "
                    + "en enchainant les TFJ dans l'ordre chronologique, jamais en fusionnant des "
                    + "journees : la fusion produirait des interets faux.");
            }
        } else if (runType == RunType.TFM) {
            requireClosableMonth(legalEntityId, businessDate);
        } else {
            requireClosableYear(legalEntityId, businessDate);
        }

        UUID runId = createRun(legalEntityId, businessDate, actorId, mode);
        LOG.info("{} {} entite {} [{}] : lancement, traitement {}", runType, businessDate,
                 legalEntityId, mode, runId);
        return execute(runId, 0);
    }

    /**
     * Un traitement de fin de mois porte sur le dernier jour d'une periode comptable, que toutes
     * les journees ont depassee et qui n'est pas encore close. Les manques de journees sont
     * l'affaire de la premiere etape, qui les nomme ; ici ne sont refusees que les demandes qui
     * n'ont pas de sens.
     */
    private void requireClosableMonth(UUID legalEntityId, LocalDate periodEnd) {
        database.inTransaction(connection -> {
            LocalDate[] bounds = Entities.periodBounds(connection, legalEntityId, periodEnd)
                .orElseThrow(() -> new TfjRefusedException(
                    "Aucune periode comptable ne couvre le " + periodEnd + "."));
            if (!bounds[1].equals(periodEnd)) {
                throw new TfjRefusedException(
                    "Le " + periodEnd + " n'est pas la fin de sa periode comptable, qui court du "
                    + bounds[0] + " au " + bounds[1] + ". L'arrete mensuel clot une periode "
                    + "entiere.");
            }
            String status = Entities.periodStatus(connection, legalEntityId, periodEnd).orElse("?");
            if ("CLOSED".equals(status)) {
                throw new TfjRefusedException(
                    "La periode se terminant le " + periodEnd + " est deja close.");
            }
            // Le dernier mois d'un exercice ne se clot pas seul : les ecritures de resultat
            // doivent lui etre imputees avant qu'il ne se ferme.
            FiscalYears.endingOn(connection, legalEntityId, periodEnd).ifPresent(year -> {
                throw new TfjRefusedException(
                    "Le mois se terminant le " + periodEnd + " clot l'exercice du " + year.start()
                    + " au " + year.end() + " : il se clot par la cloture annuelle (TFA), qui "
                    + "determine le resultat avant de fermer la periode.");
            });
            LocalDate current = Runs.currentBusinessDate(connection, legalEntityId);
            if (!current.isAfter(periodEnd)) {
                throw new TfjRefusedException(
                    "La date comptable de l'entite est le " + current + " : le mois se terminant "
                    + "le " + periodEnd + " n'est pas encore arrete jour par jour.");
            }
            return null;
        });
    }

    /**
     * Une cloture annuelle porte la date de fin d'un exercice ouvert, dont le dernier mois est
     * arrete jour par jour et pas encore clos — les ecritures de resultat lui sont imputees.
     */
    private void requireClosableYear(UUID legalEntityId, LocalDate yearEnd) {
        database.inTransaction(connection -> {
            FiscalYears.FiscalYear year = FiscalYears.endingOn(connection, legalEntityId, yearEnd)
                .orElseThrow(() -> new TfjRefusedException(
                    "Aucun exercice ne se termine le " + yearEnd + " : la cloture annuelle porte "
                    + "la date de fin d'un exercice."));
            if ("CLOSED".equals(year.status())) {
                throw new TfjRefusedException(
                    "L'exercice se terminant le " + yearEnd + " est deja clos.");
            }
            LocalDate[] bounds = Entities.periodBounds(connection, legalEntityId, yearEnd)
                .orElseThrow(() -> new TfjRefusedException(
                    "Aucune periode comptable ne couvre le " + yearEnd + "."));
            if (!bounds[1].equals(yearEnd)) {
                throw new TfjRefusedException(
                    "Le " + yearEnd + " n'est pas la fin de sa periode comptable, qui court du "
                    + bounds[0] + " au " + bounds[1] + " : un exercice se termine avec un mois.");
            }
            if ("CLOSED".equals(Entities.periodStatus(connection, legalEntityId, yearEnd)
                                    .orElse("?"))) {
                throw new TfjRefusedException(
                    "La periode se terminant le " + yearEnd + " est deja close : les ecritures "
                    + "de resultat ne pourraient pas y etre imputees.");
            }
            LocalDate current = Runs.currentBusinessDate(connection, legalEntityId);
            if (!current.isAfter(yearEnd)) {
                throw new TfjRefusedException(
                    "La date comptable de l'entite est le " + current + " : l'exercice se "
                    + "terminant le " + yearEnd + " n'est pas encore arrete jour par jour.");
            }
            return null;
        });
    }

    /** Reprend un TFJ en echec, a partir de l'etape fautive. */
    public TfjRun resume(UUID runId, UUID actorId) {
        TfjRun run = require(runId);
        if (run.status() != TfjRun.Status.FAILED) {
            throw new TfjRefusedException(
                "Seul un TFJ en echec se reprend ; celui-ci est " + run.status() + ".");
        }
        int from = run.failedStep().map(TfjRun.StepExecution::order).orElse(0);
        updateRunStatus(runId, TfjRun.Status.RUNNING, null);
        LOG.info("{} {} entite {} : reprise du traitement {} a l'etape {}", runType,
                 run.businessDate(), run.legalEntityId(), runId, from);
        return execute(runId, from);
    }

    // ------------------------------------------------------------------ execution

    private TfjRun execute(UUID runId, int fromOrder) {
        TfjRun run = require(runId);
        // Une reprise arrive par l'identifiant du traitement : l'entite est celle du traitement,
        // et la portee est posee (ou confirmee, si l'appelant l'a deja posee) avant toute etape.
        try (Database.EntityScope scope = Database.enterEntity(run.legalEntityId())) {
            return executeWithin(runId, run, fromOrder);
        }
    }

    private TfjRun executeWithin(UUID runId, TfjRun run, int fromOrder) {
        TfjContext context = new TfjContext(run.legalEntityId(), run.businessDate(), runId,
                                            startedBy(runId), run.mode());

        if (run.mode() == RunMode.DRY_RUN) {
            // Le traitement complet s'execute dans une transaction annulee a la fin. Les statuts
            // d'etape, eux, sont ecrits dans des transactions independantes : le rapport doit
            // survivre a l'annulation, sans quoi le TFJ a blanc ne laisserait aucune trace.
            database.inRolledBackTransaction(connection -> {
                runSteps(context, fromOrder);
                return null;
            });
        } else {
            runSteps(context, fromOrder);
        }

        TfjRun after = require(runId);
        TfjRun.Status status = after.failedStep().isPresent()
            ? TfjRun.Status.FAILED : TfjRun.Status.COMPLETED;
        java.time.Instant finishedAt = java.time.Instant.now();
        updateRunStatus(runId, status, finishedAt);
        long millis = after.startedAt() == null ? -1
            : java.time.Duration.between(after.startedAt(), finishedAt).toMillis();
        if (status == TfjRun.Status.COMPLETED) {
            LOG.info("{} {} entite {} : termine en {} ms, traitement {}", runType,
                     run.businessDate(), run.legalEntityId(), millis, runId);
        } else {
            TfjRun.StepExecution failed = after.failedStep().orElseThrow();
            LOG.error("{} {} entite {} : EN ECHEC a l'etape {} {} apres {} ms, traitement {} — {}",
                      runType, run.businessDate(), run.legalEntityId(), failed.order(),
                      failed.name(), millis, runId,
                      failed.error() == null ? failed.anomalies() : failed.error());
        }
        return require(runId);
    }

    private void runSteps(TfjContext context, int fromOrder) {
        List<TfjRun.StepExecution> planned = require(context.runId()).steps();

        for (TfjRun.StepExecution planStep : planned) {
            if (planStep.order() < fromOrder
                || planStep.status() == TfjRun.StepExecution.Status.COMPLETED) {
                continue;
            }
            TfjStep step = steps.get(planStep.order());
            markStep(context.runId(), planStep.order(), TfjRun.StepExecution.Status.RUNNING,
                     StepResult.none(), null);

            long startedAt = System.nanoTime();
            try {
                StepResult result = step.execute(context);
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                boolean blockedByAnomaly = step.blocking() && result.hasAnomalies();
                markStep(context.runId(), planStep.order(),
                         blockedByAnomaly ? TfjRun.StepExecution.Status.FAILED
                                          : TfjRun.StepExecution.Status.COMPLETED,
                         result,
                         blockedByAnomaly ? "anomalies bloquantes : " + result.anomalies() : null);
                if (blockedByAnomaly) {
                    LOG.error("{} {} etape {} {} : BLOQUEE apres {} ms, lu {}, ecrit {} — {}",
                              runType, context.businessDate(), planStep.order(), step.name(),
                              millis, result.read(), result.written(), result.anomalies());
                    return;
                }
                if (result.hasAnomalies()) {
                    LOG.warn("{} {} etape {} {} : terminee en {} ms avec anomalies non bloquantes,"
                             + " lu {}, ecrit {} — {}", runType, context.businessDate(),
                             planStep.order(), step.name(), millis, result.read(),
                             result.written(), result.anomalies());
                } else {
                    LOG.info("{} {} etape {} {} : terminee en {} ms, lu {}, ecrit {}", runType,
                             context.businessDate(), planStep.order(), step.name(), millis,
                             result.read(), result.written());
                }
            } catch (RuntimeException e) {
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                markStep(context.runId(), planStep.order(), TfjRun.StepExecution.Status.FAILED,
                         StepResult.none(), message(e));
                LOG.error("{} {} etape {} {} : EN ECHEC apres {} ms{}", runType,
                          context.businessDate(), planStep.order(), step.name(), millis,
                          step.blocking() ? ", la journee s'arrete" : ", la journee continue", e);
                if (step.blocking()) {
                    return;
                }
            }
        }
    }

    private static String message(RuntimeException e) {
        return e.getClass().getSimpleName() + " : " + e.getMessage();
    }

    // ------------------------------------------------------------------ annulation

    /**
     * Annule un TFJ : contre-passe toutes ses ecritures, neutralise les interets qu'il a calcules,
     * et restaure la date comptable de l'entite.
     *
     * <p>Les ecritures d'origine ne sont pas modifiees ni supprimees : le journal reste immuable, et
     * l'annulation y figure comme une serie d'extournes datees et motivees.
     */
    public TfjRun cancel(UUID runId, UUID actorId, LocalDate reversalBookingDate, String reason) {
        TfjRun run = require(runId);
        try (Database.EntityScope scope = Database.enterEntity(run.legalEntityId())) {
            return cancelWithin(runId, run, actorId, reversalBookingDate, reason);
        }
    }

    private TfjRun cancelWithin(UUID runId, TfjRun run, UUID actorId,
                                LocalDate reversalBookingDate, String reason) {
        if (run.mode() != RunMode.REAL) {
            throw new TfjRefusedException("Un TFJ a blanc n'a rien laisse : il n'y a rien a annuler.");
        }
        if (run.status() == TfjRun.Status.CANCELLED) {
            return run;
        }
        if (run.status() == TfjRun.Status.RUNNING) {
            throw new TfjRefusedException("Un TFJ en cours ne s'annule pas : l'interrompre d'abord.");
        }
        if (reason == null || reason.isBlank()) {
            throw new TfjRefusedException("Motif d'annulation obligatoire.");
        }
        // Une journee ne s'annule pas sous une journee suivante deja arretee. Restaurer la date a
        // N alors que N+1 a tourne laisserait N+1 tenue pour faite sur un etat que ses ecritures
        // ne decrivent plus ; rejouer N puis relancer N+1 rendrait l'ancien rapport sans rien
        // recalculer. Les annulations se font de la plus recente a la plus ancienne. Un mois clos
        // ne se rouvre pas non plus sous un mois suivant deja clos.
        laterRealRun(run.legalEntityId(), run.businessDate()).ifPresent(later -> {
            throw new TfjRefusedException(
                "Le " + runType + " du " + later + " a ete execute apres celui du "
                + run.businessDate() + ". Les traitements s'annulent du plus recent au plus "
                + "ancien : annuler celui-ci d'abord laisserait le suivant arrete sur un etat que "
                + "ses ecritures ne decrivent plus.");
        });

        if (runType == RunType.TFA) {
            // Les ecritures de resultat se contre-passent a la date qu'elles portent, dans la
            // periode rouverte pour cela : datees plus tard, elles laisseraient les comptes de
            // resultat soldes en date de fin d'exercice, et la cloture rejouee ne trouverait
            // rien a solder.
            if (!reversalBookingDate.equals(run.businessDate())) {
                throw new TfjRefusedException(
                    "L'annulation d'une cloture annuelle se date de la fin d'exercice, le "
                    + run.businessDate() + ", dans la periode rouverte — pas du "
                    + reversalBookingDate + ".");
            }
            database.inTransaction(connection -> {
                // Sous le verrou de l'exercice, comme l'affectation : un resultat affecte n'est
                // plus a la disposition de la cloture, l'affectation se contre-passe d'abord,
                // par une decision qui se voit — et une affectation en cours est vue, pas
                // doublee par la reouverture.
                FiscalYears.endingOn(connection, run.legalEntityId(), run.businessDate())
                    .map(year -> FiscalYears.lock(connection, year.id()))
                    .flatMap(year -> FiscalYears.currentAppropriation(connection, year.id()))
                    .ifPresent(appropriation -> {
                        throw new TfjRefusedException(
                            "Le resultat de l'exercice clos le " + run.businessDate()
                            + " est affecte (ecriture " + appropriation.entryId() + " du "
                            + appropriation.bookingDate() + ") : contre-passer l'affectation "
                            + "avant d'annuler la cloture.");
                    });
                Entities.periodBounds(connection, run.legalEntityId(), run.businessDate())
                    .ifPresent(bounds -> Entities.reopenPeriod(connection, run.legalEntityId(),
                                                               bounds[0]));
                FiscalYears.endingOn(connection, run.legalEntityId(), run.businessDate())
                    .ifPresent(year -> FiscalYears.reopen(connection, year.id()));
                return null;
            });
        }
        if (runType == RunType.TFM) {
            // Un mois ne se rouvre pas sous un exercice clos : le resultat de l'exercice a ete
            // determine avec lui. La cloture annuelle s'annule d'abord, et elle le dit.
            database.inTransaction(connection -> {
                FiscalYears.covering(connection, run.legalEntityId(), run.businessDate())
                    .filter(year -> "CLOSED".equals(year.status()))
                    .ifPresent(year -> {
                        throw new TfjRefusedException(
                            "Le mois se terminant le " + run.businessDate()
                            + " appartient a l'exercice clos du " + year.start() + " au "
                            + year.end() + " : annuler la cloture annuelle avant de rouvrir un "
                            + "de ses mois.");
                    });
                return null;
            });
        }

        if (runType == RunType.TFJ) {
            // Ce que l'arrete a execute et que la suite a fait avancer ne se defait plus : le
            // refus vient avant la premiere contre-passation, pas au milieu.
            database.inTransaction(connection -> {
                try {
                    io.corebanking.deposits.DirectDebitService.requireCancellable(connection, runId);
                } catch (IllegalStateException e) {
                    throw new TfjRefusedException(e.getMessage());
                }
                return null;
            });
        }
        List<PostedEntry> entries = entriesOf(runId);
        for (PostedEntry entry : entries) {
            postingService.reverse(entry.id(), entry.bookingDate(), reversalBookingDate,
                IdempotencyKey.forBatch(runId.toString(), "TFJ_CANCEL", entry.id()),
                "Annulation du " + runType + " du " + run.businessDate() + " — " + reason);
        }

        TfjRun cancelled = database.inTransaction(connection -> {
            if (runType == RunType.TFJ) {
                neutraliseAccruals(connection, runId);
                neutraliseFees(connection, runId);
                neutraliseLoanDues(connection, runId);
                neutraliseMobilisation(connection, runId);
                neutraliseLoanClosures(connection, runId);
                neutraliseDeposits(connection, runId, reversalBookingDate, actorId);
                setBusinessDate(connection, run.legalEntityId(), run.businessDate());
            } else if (runType == RunType.TFM) {
                // La cloture est defaite, et elle laisse une trace : REOPENED n'est pas OPEN.
                Entities.periodBounds(connection, run.legalEntityId(), run.businessDate())
                    .ifPresent(bounds -> Entities.reopenPeriod(connection, run.legalEntityId(),
                                                               bounds[0]));
            }
            markCancelled(connection, runId, actorId, reason);
            // Lu dans la transaction qui annule : appele sous une transaction englobante — la
            // double validation —, une lecture independante rendrait l'etat d'avant.
            return loadRun(connection, runId).orElseThrow();
        });
        LOG.warn("{} {} entite {} : ANNULE par {} — {} ; {} ecritures contre-passees en date du {},"
                 + " traitement {}", runType, run.businessDate(), run.legalEntityId(), actorId,
                 reason, entries.size(), reversalBookingDate, runId);
        return cancelled;
    }

    // ------------------------------------------------------------------ acces aux donnees

    private record PostedEntry(UUID id, LocalDate bookingDate) {}

    private List<PostedEntry> entriesOf(UUID runId) {
        return database.inTransaction(connection -> {
            List<PostedEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.id, e.booking_date FROM journal_entry e"
                + " LEFT JOIN journal_reversal r ON r.reversed_entry_id = e.id"
                + " WHERE e.batch_run_id = ? AND r.reversed_entry_id IS NULL"
                + " ORDER BY e.entry_number")) {
                ps.setObject(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new PostedEntry(rs.getObject(1, UUID.class),
                                                    rs.getObject(2, LocalDate.class)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des ecritures du TFJ", e);
            }
            return entries;
        });
    }

    /**
     * Rend exigibles les periodes de commission facturees par le traitement annule.
     *
     * <p>Sans cela, l'annulation contre-passerait les ecritures tout en laissant les periodes
     * marquees comme facturees : les commissions de la journee seraient perdues definitivement, et
     * l'ecart n'apparaitrait dans aucun controle — le journal, lui, serait equilibre.
     */
    private void neutraliseFees(Connection connection, UUID runId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE fee_charge SET outcome = 'CANCELLED'"
            + " WHERE batch_run_id = ? AND outcome <> 'CANCELLED'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des commissions du TFJ", e);
        }
    }

    /**
     * Rend a nouveau exigibles les echeances de credit reclamees par le traitement annule, et
     * neutralise les creances qu'il a produites.
     *
     * <p>Sans cela, les ecritures seraient contre-passees et les echeances resteraient marquees
     * comme reclamees : le client n'aurait plus rien a payer pour ce mois-la, le compteur de jours
     * de retard ne demarrerait jamais, et aucun controle comptable ne verrait l'ecart.
     */
    private void neutraliseLoanDues(Connection connection, UUID runId) {
        // Les journees d'etalement des interets courus sur credits, et les marques de suspension
        // posees par le traitement : le sous-livre du credit ne doit plus rien affirmer que ses
        // ecritures, contre-passees, ne portent plus.
        LoanStore.cancelInterestAccruals(connection, runId);
        // Les interets de retard imputes par le traitement sont repris avant que ses journees ne
        // soient neutralisees : l'ordre importe, la reprise se calculant sur les journees actives.
        try (PreparedStatement ps = connection.prepareStatement(
            // La creance qui retombe a zero est annulee et non soldee : rien n'a ete encaisse,
            // l'accrual n'a simplement plus lieu d'etre. La distinction compte — une creance
            // soldee et une creance annulee ne se racontent pas de la meme facon.
            "UPDATE loan_receivable r"
            + "   SET original_amount = r.original_amount - a.total,"
            + "       outstanding = r.outstanding - a.total,"
            + "       cancelled = (r.original_amount - a.total = 0)"
            + "  FROM (SELECT contract_id, SUM(posted_delta) AS total FROM loan_late_accrual"
            + "         WHERE batch_run_id = ? AND status = 'ACTIVE' GROUP BY contract_id) a"
            + " WHERE r.contract_id = a.contract_id AND r.category = 'LATE_INTEREST'"
            + "   AND NOT r.cancelled AND a.total > 0")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reprise des interets de retard du TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_late_accrual SET status = 'REVERSED'"
            + " WHERE batch_run_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des journees de retard du TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_classification SET status = 'REVERSED'"
            + " WHERE batch_run_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des classifications du TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_schedule_line SET made_due_on = NULL, made_due_run_id = NULL"
            + " WHERE made_due_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture des echeances de credit du TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_receivable SET cancelled = TRUE, outstanding = 0"
            + " WHERE batch_run_id = ? AND NOT cancelled")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des creances de credit du TFJ", e);
        }
    }

    /**
     * Rouvre les mobilisations closes par le traitement annule et rend leurs periodes
     * intercalaires a nouveau facturables.
     *
     * <p>Quatre effets a defaire, et l'ordre importe : les periodes facturees, le curseur de
     * facturation, les tranches tombees a la date limite, et l'echeancier definitif publie sur le
     * capital tire. Sans cela, les ecritures seraient contre-passees mais le credit resterait
     * amortissable sur un capital que plus aucune ecriture ne justifie — et la mobilisation,
     * close, ne pourrait plus recevoir la tranche qui restait a verser.
     */
    private void neutraliseMobilisation(Connection connection, UUID runId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_interim_interest SET status = 'REVERSED'"
            + " WHERE batch_run_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reprise des interets intercalaires du TFJ", e);
        }
        // Le curseur revient a la veille de la premiere periode reprise : les journees redeviennent
        // facturables exactement la ou elles l'etaient avant le traitement.
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_mobilisation m SET interim_billed_through = x.restart"
            + "  FROM (SELECT contract_id, MIN(period_start) - 1 AS restart"
            + "          FROM loan_interim_interest WHERE batch_run_id = ?"
            + "         GROUP BY contract_id) x"
            + " WHERE m.contract_id = x.contract_id AND m.interim_billed_through > x.restart")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Recul du curseur des interets intercalaires", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM loan_schedule WHERE created_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Retrait de l'echeancier publie par le TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_tranche SET status = 'PLANNED', cancelled_on = NULL,"
            + " cancellation_reason = NULL, cancelled_run_id = NULL WHERE cancelled_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Retablissement des tranches annulees par le TFJ", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_mobilisation SET closed_on = NULL, closed_run_id = NULL"
            + " WHERE closed_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture des mobilisations closes par le TFJ", e);
        }
    }

    /**
     * Rend actifs les credits clos par le traitement annule — soldes, ou jamais tires.
     *
     * <p>Une cloture n'est pas plus definitive que l'arrete qui l'a prononcee : le contrat porte
     * le traitement qui l'a clos, et c'est lui qui le rouvre.
     */
    private void neutraliseLoanClosures(Connection connection, UUID runId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_contract SET status = 'ACTIVE', closed_on = NULL, closed_run_id = NULL"
            + " WHERE closed_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture des credits clos par le TFJ", e);
        }
    }

    /**
     * Journees d'interets et reglements du traitement annule, neutralises, et positions
     * reconstruites depuis ce qui reste actif : le module d'interets sait le faire, et lui seul
     * sait ce qu'une position doit affirmer.
     */
    private void neutraliseAccruals(Connection connection, UUID runId) {
        InterestPositions.cancelRun(connection, runId);
    }

    /**
     * Blocages de montant reposes, dormances defaites, revues de connaissance client restaurees,
     * prelevements rendus a l'attente : ce que l'arrete a prononce se defait avec lui.
     */
    private void neutraliseDeposits(Connection connection, UUID runId, LocalDate on,
                                    UUID actorId) {
        Holds.cancelRun(connection, runId);
        Dormancy.cancelRun(connection, runId);
        KycReviews.cancelRun(connection, runId);
        io.corebanking.party.PartyDocuments.cancelRun(connection, runId);
        // Les prelevements executes par l'arrete redeviennent en attente ; leurs ecritures sont
        // deja contre-passees, leurs blocages tombent ici.
        io.corebanking.deposits.DirectDebitService.cancelRun(connection, runId, on, actorId);
    }

    private LocalDate currentBusinessDate(UUID legalEntityId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, legalEntityId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerStoreException("Entite juridique inconnue : " + legalEntityId);
                    }
                    return rs.getObject(1, LocalDate.class);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de la date comptable courante", e);
            }
        });
    }

    static void setBusinessDate(Connection connection, UUID legalEntityId, LocalDate date) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE legal_entity SET current_business_date = ? WHERE id = ?")) {
            ps.setObject(1, date);
            ps.setObject(2, legalEntityId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Bascule de la date comptable", e);
        }
    }

    private UUID createRun(UUID legalEntityId, LocalDate businessDate, UUID actorId, RunMode mode) {
        UUID runId = Ids.newId();
        database.inNewTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO batch_run(id, legal_entity_id, business_date, run_type, mode, status,"
                + " started_by) VALUES (?,?,?,?,?,'RUNNING',?)")) {
                ps.setObject(1, runId);
                ps.setObject(2, legalEntityId);
                ps.setObject(3, businessDate);
                ps.setString(4, runType.name());
                ps.setString(5, mode.name());
                ps.setObject(6, actorId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException(
                    "Creation du TFJ refusee : un traitement existe deja pour cette entite et "
                    + "cette date.", e);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO batch_step(id, run_id, step_order, step_name, blocking)"
                + " VALUES (?,?,?,?,?)")) {
                for (int order = 0; order < steps.size(); order++) {
                    ps.setObject(1, Ids.newId());
                    ps.setObject(2, runId);
                    ps.setInt(3, order);
                    ps.setString(4, steps.get(order).name());
                    ps.setBoolean(5, steps.get(order).blocking());
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new LedgerStoreException("Creation des etapes du TFJ", e);
            }
            return null;
        });
        return runId;
    }

    private void markStep(UUID runId, int order, TfjRun.StepExecution.Status status,
                          StepResult result, String error) {
        // Transaction independante : en TFJ a blanc, le rapport doit survivre a l'annulation.
        database.inNewTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE batch_step SET status = ?, read_count = ?, write_count = ?, anomalies = ?,"
                + " error_detail = ?,"
                + " started_at = COALESCE(started_at, now()),"
                + " finished_at = CASE WHEN ? IN ('COMPLETED','FAILED') THEN now() ELSE NULL END"
                + " WHERE run_id = ? AND step_order = ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, result.read());
                ps.setLong(3, result.written());
                ps.setString(4, result.anomalies().isEmpty() ? null
                                                             : String.join(" | ", result.anomalies()));
                ps.setString(5, error);
                ps.setString(6, status.name());
                ps.setObject(7, runId);
                ps.setInt(8, order);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Mise a jour de l'etape " + order, e);
            }
            return null;
        });
    }

    private void updateRunStatus(UUID runId, TfjRun.Status status, java.time.Instant finishedAt) {
        database.inNewTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE batch_run SET status = ?, finished_at = ? WHERE id = ?")) {
                ps.setString(1, status.name());
                ps.setTimestamp(2, finishedAt == null ? null : Timestamp.from(finishedAt));
                ps.setObject(3, runId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Mise a jour du statut du TFJ", e);
            }
            return null;
        });
    }

    private void markCancelled(Connection connection, UUID runId, UUID actorId, String reason) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE batch_run SET status = 'CANCELLED', cancelled_by = ?, cancel_reason = ?,"
            + " finished_at = now() WHERE id = ?")) {
            ps.setObject(1, actorId);
            ps.setString(2, reason);
            ps.setObject(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation du TFJ", e);
        }
    }

    private UUID startedBy(UUID runId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT started_by FROM batch_run WHERE id = ?")) {
                ps.setObject(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de l'auteur du TFJ", e);
            }
        });
    }

    public TfjRun require(UUID runId) {
        return find(runId).orElseThrow(
            () -> new TfjRefusedException("TFJ introuvable : " + runId));
    }

    public Optional<TfjRun> find(UUID runId) {
        return database.inNewTransaction(connection -> loadRun(connection, runId));
    }

    private Optional<TfjRun> findRealRun(UUID legalEntityId, LocalDate businessDate) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM batch_run WHERE legal_entity_id = ? AND business_date = ?"
                + " AND run_type = ? AND mode = 'REAL' AND status <> 'CANCELLED'")) {
                ps.setObject(1, legalEntityId);
                ps.setObject(2, businessDate);
                ps.setString(3, runType.name());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? loadRun(connection, rs.getObject(1, UUID.class))
                                     : Optional.<TfjRun>empty();
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recherche du TFJ de la journee", e);
            }
        });
    }

    /** Journee reelle, non annulee, arretee apres la date donnee — la plus proche. */
    private Optional<LocalDate> laterRealRun(UUID legalEntityId, LocalDate businessDate) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MIN(business_date) FROM batch_run"
                + " WHERE legal_entity_id = ? AND business_date > ? AND run_type = ?"
                + "   AND mode = 'REAL' AND status <> 'CANCELLED'")) {
                ps.setObject(1, legalEntityId);
                ps.setObject(2, businessDate);
                ps.setString(3, runType.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return Optional.ofNullable(rs.getObject(1, LocalDate.class));
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recherche des journees posterieures", e);
            }
        });
    }

    private Optional<TfjRun> loadRun(Connection connection, UUID runId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT legal_entity_id, business_date, mode, status, started_at, finished_at"
            + " FROM batch_run WHERE id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp finished = rs.getTimestamp(6);
                return Optional.of(new TfjRun(
                    runId,
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, LocalDate.class),
                    RunMode.valueOf(rs.getString(3)),
                    TfjRun.Status.valueOf(rs.getString(4)),
                    loadSteps(connection, runId),
                    rs.getTimestamp(5).toInstant(),
                    finished == null ? null : finished.toInstant()));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du TFJ " + runId, e);
        }
    }

    private List<TfjRun.StepExecution> loadSteps(Connection connection, UUID runId) {
        List<TfjRun.StepExecution> executions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT step_order, step_name, blocking, status, read_count, write_count, anomalies,"
            + " error_detail FROM batch_step WHERE run_id = ? ORDER BY step_order")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String anomalies = rs.getString(7);
                    executions.add(new TfjRun.StepExecution(
                        rs.getInt(1), rs.getString(2), rs.getBoolean(3),
                        TfjRun.StepExecution.Status.valueOf(rs.getString(4)),
                        rs.getLong(5), rs.getLong(6),
                        anomalies == null ? List.of() : List.of(anomalies.split(" \\| ")),
                        rs.getString(8)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des etapes du TFJ " + runId, e);
        }
        return executions;
    }

    /** Traitement refuse : l'etat du systeme ne permet pas de le lancer. */
    public static class TfjRefusedException extends RuntimeException {
        public TfjRefusedException(String message) {
            super(message);
        }
    }
}
