package io.corebanking.loan.service;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AmortisationMethod;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.DueCategory;
import io.corebanking.loan.InsuranceBasis;
import io.corebanking.loan.Instalment;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.Receivable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acces aux contrats, echeanciers, creances et reglements. */
public final class LoanStore {

    private LoanStore() {}

    // ------------------------------------------------------------------ contrats

    public record ContractDraft(
        UUID legalEntityId, String reference, String productCode, CurrencyRef currency,
        UUID loanAccountId, UUID settlementAccountId, Money principal, LocalDate disbursedOn,
        UUID createdBy) {}

    public static UUID createContract(Connection c, ContractDraft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_contract(id, legal_entity_id, reference, product_code, currency,"
            + " loan_account_id, settlement_account_id, principal, disbursed_on, status, created_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,'DRAFT',?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.reference());
            ps.setString(4, draft.productCode());
            ps.setString(5, draft.currency().code());
            ps.setObject(6, draft.loanAccountId());
            ps.setObject(7, draft.settlementAccountId());
            ps.setBigDecimal(8, draft.principal().amount());
            ps.setObject(9, draft.disbursedOn());
            ps.setObject(10, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du contrat " + draft.reference(), e);
        }
        return id;
    }

    public static void activate(Connection c, UUID contractId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_contract SET status = 'ACTIVE', approved_by = ?"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, contractId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Contrat " + contractId + " introuvable ou deja sorti de l'etat DRAFT.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation du contrat " + contractId, e);
        }
    }

    public static LoanContract requireContract(Connection c, UUID contractId) {
        return findContract(c, contractId).orElseThrow(
            () -> new LedgerStoreException("Contrat de credit introuvable : " + contractId));
    }

    public static Optional<LoanContract> findContract(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.id, l.legal_entity_id, l.reference, l.product_code, cur.code, cur.scale,"
            + " cur.rounding_mode, l.loan_account_id, l.settlement_account_id, l.principal,"
            + " l.disbursed_on, l.status FROM loan_contract l"
            + " JOIN currency cur ON cur.code = l.currency WHERE l.id = ?")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readContract(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du contrat " + contractId, e);
        }
    }

    /** Contrats actifs d'une entite, dans un ordre stable. */
    public static List<LoanContract> activeContracts(Connection c, UUID legalEntityId) {
        List<LoanContract> contracts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.id, l.legal_entity_id, l.reference, l.product_code, cur.code, cur.scale,"
            + " cur.rounding_mode, l.loan_account_id, l.settlement_account_id, l.principal,"
            + " l.disbursed_on, l.status FROM loan_contract l"
            + " JOIN currency cur ON cur.code = l.currency"
            + " WHERE l.legal_entity_id = ? AND l.status = 'ACTIVE' ORDER BY l.id")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contracts.add(readContract(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des credits en cours", e);
        }
        return contracts;
    }

    private static LoanContract readContract(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(5), rs.getInt(6),
                                               RoundingMode.valueOf(rs.getString(7)));
        return new LoanContract(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getString(4), currency, rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
            Money.of(rs.getBigDecimal(10), currency), rs.getObject(11, LocalDate.class),
            LoanContract.Status.valueOf(rs.getString(12)));
    }

    // ------------------------------------------------------------------ echeanciers

    public enum ScheduleReason { INITIAL, RESCHEDULING, EARLY_REPAYMENT, RATE_REVISION }

    /**
     * Publie une version d'echeancier et clot la precedente a la veille de sa prise d'effet.
     *
     * <p>La contrainte d'exclusion de la base garantit qu'il n'en existe jamais deux en vigueur a
     * la meme date ; ce code se contente de fermer proprement, et echoue bruyamment s'il ne le
     * fait pas correctement.
     */
    public static UUID publishSchedule(Connection c, UUID contractId, AmortisationSchedule schedule,
                                       ScheduleReason reason, LocalDate effectiveFrom,
                                       UUID createdBy, UUID approvedBy) {
        int version = nextVersion(c, contractId);
        if (version > 1) {
            requireStartsAtEffectiveDate(schedule, effectiveFrom, contractId);
            supersede(c, contractId, effectiveFrom.minusDays(1));
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_schedule(id, contract_id, version, reason, effective_from,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contractId);
            ps.setInt(3, version);
            ps.setString(4, reason.name());
            ps.setObject(5, effectiveFrom);
            ps.setObject(6, createdBy);
            ps.setObject(7, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Publication de l'echeancier du contrat " + contractId, e);
        }
        insertLines(c, id, schedule);
        return id;
    }

    /**
     * Un plan de remplacement ne porte que sur l'avenir.
     *
     * <p>Une version dont les echeances commencent avant sa prise d'effet reclamerait une seconde
     * fois des echeances deja rendues exigibles sur la version precedente : le client verrait
     * apparaitre des creances en double, et le rapprochement avec l'ancien plan serait impossible.
     * Un rechelonnement se construit sur le capital restant du, a compter de sa date d'effet.
     */
    private static void requireStartsAtEffectiveDate(AmortisationSchedule schedule,
                                                     LocalDate effectiveFrom, UUID contractId) {
        Instalment first = schedule.instalments().get(0);
        if (first.dueDate().isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                "Le nouvel echeancier du contrat " + contractId + " s'ouvre sur une echeance au "
                + first.dueDate() + ", anterieure a sa prise d'effet du " + effectiveFrom
                + ". Un plan de remplacement porte sur le capital restant du, a compter de sa date "
                + "d'effet : reprendre des echeances deja exigibles les reclamerait deux fois.");
        }
    }

    private static int nextVersion(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM loan_schedule WHERE contract_id = ?")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la version d'echeancier", e);
        }
    }

    private static void supersede(Connection c, UUID contractId, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_schedule SET superseded_on = ?"
            + " WHERE contract_id = ? AND superseded_on IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, contractId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de l'echeancier precedent", e);
        }
    }

    private static void insertLines(Connection c, UUID scheduleId, AmortisationSchedule schedule) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_schedule_line(schedule_id, number, due_date, period_start, period_end,"
            + " outstanding_before, principal, interest, insurance, fee, tax, total,"
            + " outstanding_after) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Instalment instalment : schedule.instalments()) {
                ps.setObject(1, scheduleId);
                ps.setInt(2, instalment.number());
                ps.setObject(3, instalment.dueDate());
                ps.setObject(4, instalment.periodStart());
                ps.setObject(5, instalment.periodEnd());
                ps.setBigDecimal(6, instalment.outstandingBefore().amount());
                ps.setBigDecimal(7, instalment.principal().amount());
                ps.setBigDecimal(8, instalment.interest().amount());
                ps.setBigDecimal(9, instalment.insurance().amount());
                ps.setBigDecimal(10, instalment.fee().amount());
                ps.setBigDecimal(11, instalment.tax().amount());
                ps.setBigDecimal(12, instalment.total().amount());
                ps.setBigDecimal(13, instalment.outstandingAfter().amount());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des echeances", e);
        }
    }

    /** Une echeance exigible, telle qu'elle est relue par le traitement. */
    public record DueLine(
        UUID scheduleId, int number, LocalDate dueDate, Money principal, Money interest,
        Money insurance, Money fee, Money tax, Money total) {

        public Money charges() {
            return interest.plus(insurance).plus(fee).plus(tax);
        }
    }

    /**
     * Echeances echues a la date traitee et pas encore rendues exigibles, pour un contrat.
     *
     * <p>Seul l'echeancier en vigueur a la date traitee est consulte. Un rechelonnement rend les
     * echeances de l'ancienne version sans objet : les reclamer reviendrait a facturer un plan de
     * remboursement que le client ne doit plus.
     */
    public static List<DueLine> instalmentsDueOn(Connection c, UUID contractId,
                                                 CurrencyRef currency, LocalDate businessDate) {
        List<DueLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.schedule_id, l.number, l.due_date, l.principal, l.interest, l.insurance,"
            + "       l.fee, l.tax, l.total"
            + "  FROM loan_schedule_line l JOIN loan_schedule s ON s.id = l.schedule_id"
            + " WHERE s.contract_id = ? AND l.made_due_on IS NULL AND l.due_date <= ?"
            + "   AND s.effective_from <= ? AND (s.superseded_on IS NULL OR s.superseded_on >= ?)"
            + " ORDER BY l.due_date, l.number")) {
            ps.setObject(1, contractId);
            ps.setObject(2, businessDate);
            ps.setObject(3, businessDate);
            ps.setObject(4, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new DueLine(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, LocalDate.class),
                        Money.of(rs.getBigDecimal(4), currency),
                        Money.of(rs.getBigDecimal(5), currency),
                        Money.of(rs.getBigDecimal(6), currency),
                        Money.of(rs.getBigDecimal(7), currency),
                        Money.of(rs.getBigDecimal(8), currency),
                        Money.of(rs.getBigDecimal(9), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des echeances exigibles", e);
        }
        return lines;
    }

    public static void markDue(Connection c, UUID scheduleId, int number, LocalDate businessDate,
                               UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_schedule_line SET made_due_on = ?, made_due_run_id = ?"
            + " WHERE schedule_id = ? AND number = ? AND made_due_on IS NULL")) {
            ps.setObject(1, businessDate);
            ps.setObject(2, runId);
            ps.setObject(3, scheduleId);
            ps.setInt(4, number);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Echeance " + number + " deja rendue exigible : une echeance ne se reclame "
                    + "pas deux fois.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Marquage de l'echeance " + number, e);
        }
    }

    // ------------------------------------------------------------------ creances

    public static UUID addReceivable(Connection c, UUID contractId, UUID scheduleId, int number,
                                     DueCategory category, LocalDate dueDate, Money amount,
                                     UUID batchRunId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_receivable(id, contract_id, schedule_id, instalment_number, category,"
            + " due_date, original_amount, outstanding, batch_run_id) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contractId);
            ps.setObject(3, scheduleId);
            ps.setInt(4, number);
            ps.setString(5, category.name());
            ps.setObject(6, dueDate);
            ps.setBigDecimal(7, amount.amount());
            ps.setBigDecimal(8, amount.amount());
            ps.setObject(9, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Creation de la creance " + category + " de l'echeance " + number, e);
        }
        return id;
    }

    /** Creances ouvertes d'un contrat, de la plus ancienne a la plus recente. */
    public static List<Receivable> openReceivables(Connection c, UUID contractId,
                                                   CurrencyRef currency) {
        List<Receivable> receivables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, instalment_number, category, due_date, outstanding FROM loan_receivable"
            + " WHERE contract_id = ? AND outstanding > 0 AND NOT cancelled"
            + " ORDER BY due_date, instalment_number, category")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    receivables.add(new Receivable(
                        rs.getObject(1, UUID.class), rs.getInt(2),
                        DueCategory.valueOf(rs.getString(3)), rs.getObject(4, LocalDate.class),
                        Money.of(rs.getBigDecimal(5), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des creances du contrat " + contractId, e);
        }
        return receivables;
    }

    public static void reduceReceivable(Connection c, UUID receivableId, Money amount,
                                        LocalDate valueDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_receivable SET outstanding = outstanding - ?,"
            + " settled_on = CASE WHEN outstanding - ? = 0 THEN ? ELSE settled_on END"
            + " WHERE id = ? AND outstanding >= ?")) {
            ps.setBigDecimal(1, amount.amount());
            ps.setBigDecimal(2, amount.amount());
            ps.setObject(3, valueDate);
            ps.setObject(4, receivableId);
            ps.setBigDecimal(5, amount.amount());
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Imputation de " + amount + " refusee sur la creance " + receivableId
                    + " : son solde a change depuis la lecture.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Imputation sur la creance " + receivableId, e);
        }
    }

    /**
     * Neutralise les creances produites par un traitement annule et rend les echeances a nouveau
     * exigibles.
     */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_schedule_line SET made_due_on = NULL, made_due_run_id = NULL"
            + " WHERE made_due_run_id = ?")) {
            ps.setObject(1, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reouverture des echeances du traitement " + batchRunId, e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_receivable SET cancelled = TRUE, outstanding = 0"
            + " WHERE batch_run_id = ? AND NOT cancelled")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des creances du traitement " + batchRunId, e);
        }
    }

    /** Date d'echeance de la plus ancienne creance non soldee, absente si le contrat est a jour. */
    public static Optional<LocalDate> oldestUnpaid(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MIN(due_date) FROM loan_receivable"
            + " WHERE contract_id = ? AND outstanding > 0 AND NOT cancelled")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'impaye le plus ancien", e);
        }
    }

    // ------------------------------------------------------------------ reglements

    public static UUID recordPayment(Connection c, UUID contractId, LocalDate valueDate,
                                     Money amount, Money allocated, String source, UUID entryId,
                                     UUID batchRunId, String idempotencyKey, UUID actorId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_payment(id, contract_id, value_date, amount, allocated, unallocated,"
            + " source, entry_id, batch_run_id, idempotency_key, actor_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contractId);
            ps.setObject(3, valueDate);
            ps.setBigDecimal(4, amount.amount());
            ps.setBigDecimal(5, allocated.amount());
            ps.setBigDecimal(6, amount.minus(allocated).amount());
            ps.setString(7, source);
            ps.setObject(8, entryId);
            ps.setObject(9, batchRunId);
            ps.setString(10, idempotencyKey);
            ps.setObject(11, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du reglement du contrat " + contractId, e);
        }
        return id;
    }

    public static void recordAllocation(Connection c, UUID paymentId, UUID receivableId,
                                        Money amount) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_payment_allocation(payment_id, receivable_id, amount)"
            + " VALUES (?,?,?)")) {
            ps.setObject(1, paymentId);
            ps.setObject(2, receivableId);
            ps.setBigDecimal(3, amount.amount());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Ventilation du reglement " + paymentId, e);
        }
    }

    /** Vrai si un reglement porte deja cette cle : garde de reprise du prelevement automatique. */
    public static boolean paymentExists(Connection c, String idempotencyKey) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM loan_payment WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche du reglement " + idempotencyKey, e);
        }
    }

    /** Conditions du credit relues pour regenerer un echeancier — rechelonnement, simulation. */
    public static LoanTerms terms(LoanContract contract, BigDecimal ratePercent,
                                  Periodicity frequency, int instalments, int grace,
                                  LocalDate firstDueDate, AmortisationMethod method,
                                  DayCountConvention dayCount) {
        return new LoanTerms(contract.principal(), contract.currency(), ratePercent, frequency,
                             instalments, grace, contract.disbursedOn(), firstDueDate, method,
                             dayCount, null, InsuranceBasis.NONE, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
