package io.corebanking.tfj;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
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
 */
public final class TfjEngine {

    private static final String RUN_TYPE = "TFJ";

    private final Database database;
    private final PostingService postingService;
    private final List<TfjStep> steps;

    public TfjEngine(Database database, PostingService postingService, List<TfjStep> steps) {
        this.database = database;
        this.postingService = postingService;
        this.steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Un TFJ sans etape ne signifie rien");
        }
    }

    // ------------------------------------------------------------------ lancement

    public TfjRun run(UUID legalEntityId, LocalDate businessDate, UUID actorId, RunMode mode) {
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

        LocalDate current = currentBusinessDate(legalEntityId);
        if (!businessDate.equals(current)) {
            throw new TfjRefusedException(
                "Le TFJ ne traite que la date comptable courante de l'entite, soit le " + current
                + ". Demande pour le " + businessDate + ". Un rattrapage s'effectue en enchainant "
                + "les TFJ dans l'ordre chronologique, jamais en fusionnant des journees : "
                + "la fusion produirait des interets faux.");
        }

        UUID runId = createRun(legalEntityId, businessDate, actorId, mode);
        return execute(runId, 0);
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
        return execute(runId, from);
    }

    // ------------------------------------------------------------------ execution

    private TfjRun execute(UUID runId, int fromOrder) {
        TfjRun run = require(runId);
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
        updateRunStatus(runId, status, java.time.Instant.now());
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

            try {
                StepResult result = step.execute(context);
                boolean blockedByAnomaly = step.blocking() && result.hasAnomalies();
                markStep(context.runId(), planStep.order(),
                         blockedByAnomaly ? TfjRun.StepExecution.Status.FAILED
                                          : TfjRun.StepExecution.Status.COMPLETED,
                         result,
                         blockedByAnomaly ? "anomalies bloquantes : " + result.anomalies() : null);
                if (blockedByAnomaly) {
                    return;
                }
            } catch (RuntimeException e) {
                markStep(context.runId(), planStep.order(), TfjRun.StepExecution.Status.FAILED,
                         StepResult.none(), message(e));
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

        for (PostedEntry entry : entriesOf(runId)) {
            postingService.reverse(entry.id(), entry.bookingDate(), reversalBookingDate,
                IdempotencyKey.forBatch(runId.toString(), "TFJ_CANCEL", entry.id()),
                "Annulation du TFJ du " + run.businessDate() + " — " + reason);
        }

        database.inTransaction(connection -> {
            neutraliseAccruals(connection, runId);
            neutraliseFees(connection, runId);
            neutraliseLoanDues(connection, runId);
            neutraliseMobilisation(connection, runId);
            setBusinessDate(connection, run.legalEntityId(), run.businessDate());
            markCancelled(connection, runId, actorId, reason);
            return null;
        });
        return require(runId);
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
        // Le contrat clos faute de tirage redevient actif : la date limite qui l'a fait tomber
        // appartient a une journee qui n'a plus eu lieu.
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE loan_contract l SET status = 'ACTIVE'"
            + "  FROM loan_mobilisation m"
            + " WHERE m.contract_id = l.id AND m.closed_run_id IS NULL AND m.closed_on IS NULL"
            + "   AND l.status = 'CLOSED'"
            + "   AND EXISTS (SELECT 1 FROM loan_tranche t"
            + "                WHERE t.contract_id = l.id AND t.cancelled_run_id IS NULL"
            + "                  AND t.status = 'PLANNED')")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture des credits clos par le TFJ", e);
        }
    }

    private void neutraliseAccruals(Connection connection, UUID runId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE interest_accrual SET status = 'REVERSED' WHERE batch_run_id = ?")) {
            ps.setObject(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Neutralisation des interets calcules par le TFJ annule", e);
        }
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
                ps.setString(4, RUN_TYPE);
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
                ps.setString(3, RUN_TYPE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? loadRun(connection, rs.getObject(1, UUID.class))
                                     : Optional.<TfjRun>empty();
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recherche du TFJ de la journee", e);
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
