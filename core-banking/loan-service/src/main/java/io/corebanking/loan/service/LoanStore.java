package io.corebanking.loan.service;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.product.ProductCatalog;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Cree un contrat, apres controle de ses comptes et de son produit.
     *
     * <p>Les deux comptes doivent etre des comptes clients de l'entite du contrat, tenus dans sa
     * devise ; le produit doit exister pour cette entite, dans cette devise. Chacun de ces defauts
     * etait decouvert au deblocage ou au premier arrete, par un refus du ledger ou une anomalie
     * bloquante — jamais devant celui qui saisit le contrat.
     */
    public static UUID createContract(Connection c, ContractDraft draft) {
        requireCustomerAccount(c, draft, draft.loanAccountId(), "compte de pret");
        requireCustomerAccount(c, draft, draft.settlementAccountId(), "compte de reglement");
        ProductCatalog.requireProductCurrency(c, draft.legalEntityId(), draft.productCode(),
                                              draft.currency().code(),
                                              "le contrat " + draft.reference());
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

    private static void requireCustomerAccount(Connection c, ContractDraft draft, UUID accountId,
                                               String role) {
        Account account = Accounts.loadAll(c, List.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException(
                "Contrat " + draft.reference() + " : " + role + " " + accountId + " inconnu.");
        }
        if (!account.legalEntityId().equals(draft.legalEntityId())) {
            throw new IllegalArgumentException(
                "Contrat " + draft.reference() + " : le " + role + " " + account.code()
                + " appartient a une autre entite juridique.");
        }
        if (!account.currency().equals(draft.currency())) {
            throw new IllegalArgumentException(
                "Contrat " + draft.reference() + " : le " + role + " " + account.code()
                + " est tenu en " + account.currency() + " alors que le credit est en "
                + draft.currency() + ".");
        }
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException(
                "Contrat " + draft.reference() + " : le " + role + " " + account.code()
                + " est de nature " + account.kind() + " ; un credit se porte sur des comptes "
                + "clients, l'encours doit rester lisible independamment des comptes generaux.");
        }
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

    /**
     * Fige au deblocage ce qui ne varie plus : conditions financieres, frais retenus, taux
     * effectif global.
     */
    public static void recordDisbursementTerms(Connection c, UUID contractId, LoanTerms terms,
                                               Money upfrontFees, io.corebanking.loan.Teg teg) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_contract SET upfront_fees = ?, teg_percent = ?, teg_method = ?,"
            + " annual_rate_percent = ?, frequency = ?, amortisation_method = ?, day_count = ?,"
            + " periodic_fee = ?, insurance_basis = ?, insurance_rate_percent = ?,"
            + " tax_on_interest_percent = ? WHERE id = ?")) {
            ps.setBigDecimal(1, upfrontFees.amount());
            ps.setBigDecimal(2, teg.annualRatePercent());
            ps.setString(3, teg.method().name());
            ps.setBigDecimal(4, terms.annualRatePercent());
            ps.setString(5, terms.frequency().name());
            ps.setString(6, terms.method().name());
            ps.setString(7, terms.dayCount().name());
            ps.setBigDecimal(8, terms.periodicFee().amount());
            ps.setString(9, terms.insuranceBasis().name());
            ps.setBigDecimal(10, terms.insuranceRatePercent());
            ps.setBigDecimal(11, terms.taxOnInterestRatePercent());
            ps.setObject(12, contractId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des conditions du contrat "
                                           + contractId, e);
        }
    }

    /**
     * Enregistre les conditions financieres sans arreter le taux effectif.
     *
     * <p>Un credit mobilise par tranches a besoin de ses conditions des l'ouverture — les interets
     * intercalaires s'en servent — mais son taux effectif n'est connu qu'a la cloture, quand le
     * capital tire et les dates de versement le sont. Les ecrire ensemble obligerait a inventer un
     * taux effectif provisoire et a le laisser dans la base sous le meme nom que le definitif.
     */
    public static void recordFinancialTerms(Connection c, UUID contractId, LoanTerms terms) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_contract SET annual_rate_percent = ?, frequency = ?,"
            + " amortisation_method = ?, day_count = ?, periodic_fee = ?, insurance_basis = ?,"
            + " insurance_rate_percent = ?, tax_on_interest_percent = ? WHERE id = ?")) {
            ps.setBigDecimal(1, terms.annualRatePercent());
            ps.setString(2, terms.frequency().name());
            ps.setString(3, terms.method().name());
            ps.setString(4, terms.dayCount().name());
            ps.setBigDecimal(5, terms.periodicFee().amount());
            ps.setString(6, terms.insuranceBasis().name());
            ps.setBigDecimal(7, terms.insuranceRatePercent());
            ps.setBigDecimal(8, terms.taxOnInterestRatePercent());
            ps.setObject(9, contractId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des conditions du contrat " + contractId,
                                           e);
        }
    }

    /**
     * Clot un contrat.
     *
     * @param batchRunId traitement qui prononce la cloture, nul pour une cloture en ligne. Son
     *                   annulation rend le contrat actif : une cloture n'est pas plus definitive
     *                   que l'arrete qui l'a prononcee.
     */
    public static void close(Connection c, UUID contractId, LocalDate on, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_contract SET status = 'CLOSED', closed_on = ?, closed_run_id = ?"
            + " WHERE id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, on);
            ps.setObject(2, batchRunId);
            ps.setObject(3, contractId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Contrat " + contractId + " introuvable ou deja sorti de l'etat ACTIVE.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture du contrat " + contractId, e);
        }
    }

    /**
     * Contrats actifs qui n'ont plus rien a reclamer : aucune echeance a venir sur l'echeancier en
     * vigueur, aucune creance ouverte, aucune mobilisation en cours.
     *
     * <p>L'encours n'est pas verifie ici mais par l'appelant, contrat par contrat : un capital
     * restant alors que tout est reclame et regle n'est pas un contrat a clore, c'est un ecart
     * entre le compte de pret et le sous-livre, et il doit etre nomme.
     */
    public static List<LoanContract> settledCandidates(Connection c, UUID legalEntityId) {
        List<LoanContract> contracts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_CONTRACT
            + " WHERE l.legal_entity_id = ? AND l.status = 'ACTIVE'"
            + "   AND NOT EXISTS (SELECT 1 FROM loan_mobilisation m"
            + "                    WHERE m.contract_id = l.id AND m.closed_on IS NULL)"
            + "   AND EXISTS (SELECT 1 FROM loan_schedule s WHERE s.contract_id = l.id)"
            + "   AND NOT EXISTS (SELECT 1 FROM loan_schedule_line sl"
            + "                     JOIN loan_schedule s ON s.id = sl.schedule_id"
            + "                    WHERE s.contract_id = l.id AND s.superseded_on IS NULL"
            + "                      AND sl.made_due_on IS NULL)"
            + "   AND NOT EXISTS (SELECT 1 FROM loan_receivable r"
            + "                    WHERE r.contract_id = l.id AND NOT r.cancelled"
            + "                      AND r.outstanding > 0)"
            + " ORDER BY l.id")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contracts.add(readContract(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des credits soldes", e);
        }
        return contracts;
    }

    /** Echeances a venir apres une date : ce que le remboursement anticipe va remplacer. */
    public record Remaining(int count, LocalDate nextDueDate, Money annuity) {}

    public static Optional<Remaining> remainingAfter(Connection c, UUID contractId,
                                                     CurrencyRef currency, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*), MIN(l.due_date),"
            + "       MIN(l.principal + l.interest) FILTER (WHERE l.due_date = ("
            + "           SELECT MIN(x.due_date) FROM loan_schedule_line x"
            + "            WHERE x.schedule_id = l.schedule_id AND x.due_date > ?))"
            + "  FROM loan_schedule_line l JOIN loan_schedule s ON s.id = l.schedule_id"
            + " WHERE s.contract_id = ? AND s.superseded_on IS NULL AND l.due_date > ?")) {
            ps.setObject(1, on);
            ps.setObject(2, contractId);
            ps.setObject(3, on);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                if (count == 0) {
                    return Optional.empty();
                }
                return Optional.of(new Remaining(count, rs.getObject(2, LocalDate.class),
                                                 Money.of(rs.getBigDecimal(3), currency)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des echeances a venir du contrat " + contractId,
                                           e);
        }
    }

    /** Date du dernier arrete ou le credit portait un impaye, absente s'il n'en a jamais porte. */
    public static Optional<LocalDate> lastArrearsDate(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MAX(classified_on) FROM loan_classification"
            + " WHERE contract_id = ? AND status = 'ACTIVE' AND days_past_due > 0")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Optional.ofNullable(rs.getObject(1, LocalDate.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du dernier impaye du contrat " + contractId, e);
        }
    }

    public static LoanContract requireContract(Connection c, UUID contractId) {
        return findContract(c, contractId).orElseThrow(
            () -> new LedgerStoreException("Contrat de credit introuvable : " + contractId));
    }

    public static Optional<LoanContract> findContract(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_CONTRACT + " WHERE l.id = ?")) {
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
            SELECT_CONTRACT + " WHERE l.legal_entity_id = ? AND l.status = 'ACTIVE'"
            + " ORDER BY l.id")) {
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

    private static final String SELECT_CONTRACT =
        "SELECT l.id, l.legal_entity_id, l.reference, l.product_code, cur.code, cur.scale,"
        + " cur.rounding_mode, l.loan_account_id, l.settlement_account_id, l.principal,"
        + " l.disbursed_on, l.status, l.annual_rate_percent, l.frequency, l.amortisation_method,"
        + " l.day_count, l.periodic_fee, l.insurance_basis, l.insurance_rate_percent,"
        + " l.tax_on_interest_percent"
        + "  FROM loan_contract l JOIN currency cur ON cur.code = l.currency";

    private static LoanContract readContract(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(5), rs.getInt(6),
                                               RoundingMode.valueOf(rs.getString(7)));
        Money principal = Money.of(rs.getBigDecimal(10), currency);
        LocalDate disbursedOn = rs.getObject(11, LocalDate.class);
        LoanTerms terms = readTerms(rs, currency, principal, disbursedOn);
        return new LoanContract(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getString(4), currency, rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
            principal, disbursedOn, LoanContract.Status.valueOf(rs.getString(12)), terms);
    }

    /**
     * Conditions financieres relues.
     *
     * <p>Le nombre d'echeances et la premiere echeance ne sont pas conserves sur le contrat : ils
     * appartiennent a l'echeancier, qui est versionne. Les conditions rendues ici portent donc une
     * duree d'une echeance et une premiere echeance conventionnelles, que
     * {@link LoanTerms#forRemaining} remplace des qu'un plan est reconstruit. Y stocker une duree
     * ferait exister deux verites sur le meme sujet.
     */
    private static LoanTerms readTerms(ResultSet rs, CurrencyRef currency, Money principal,
                                       LocalDate disbursedOn) throws SQLException {
        String frequency = rs.getString(14);
        if (frequency == null) {
            return null;
        }
        return new LoanTerms(
            principal, currency, rs.getBigDecimal(13), Periodicity.valueOf(frequency), 1, 0,
            disbursedOn, disbursedOn.plusDays(1),
            AmortisationMethod.valueOf(rs.getString(15)),
            DayCountConvention.valueOf(rs.getString(16)),
            Money.of(rs.getBigDecimal(17), currency),
            InsuranceBasis.valueOf(rs.getString(18)), rs.getBigDecimal(19), rs.getBigDecimal(20));
    }

    // ------------------------------------------------------------------ echeanciers

    public enum ScheduleReason {
        INITIAL, RESCHEDULING, EARLY_REPAYMENT, RATE_REVISION, MOBILISATION
    }

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
        return publishSchedule(c, contractId, schedule, reason, effectiveFrom, createdBy,
                               approvedBy, null);
    }

    /**
     * @param batchRunId traitement qui publie la version, lorsqu'elle nait d'un traitement de fin
     *                   de journee. Son annulation doit pouvoir la retirer : un echeancier publie
     *                   par un TFJ annule ne repose plus sur aucune ecriture.
     */
    public static UUID publishSchedule(Connection c, UUID contractId, AmortisationSchedule schedule,
                                       ScheduleReason reason, LocalDate effectiveFrom,
                                       UUID createdBy, UUID approvedBy, UUID batchRunId) {
        int version = nextVersion(c, contractId);
        if (version > 1) {
            requireStartsAtEffectiveDate(schedule, effectiveFrom, contractId);
            supersede(c, contractId, effectiveFrom.minusDays(1));
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_schedule(id, contract_id, version, reason, effective_from,"
            + " created_by, approved_by, created_run_id) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contractId);
            ps.setInt(3, version);
            ps.setString(4, reason.name());
            ps.setObject(5, effectiveFrom);
            ps.setObject(6, createdBy);
            ps.setObject(7, approvedBy);
            ps.setObject(8, batchRunId);
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

    // ------------------------------------------------------------------ retard

    /** Creance telle qu'elle est lue pour reconstituer l'assiette de retard, jour par jour. */
    public record OverdueLine(UUID id, UUID scheduleId, int instalmentNumber, DueCategory category,
                              LocalDate dueDate, Money originalAmount) {}

    /** Reglement impute sur une creance, avec la date qui decide de la journee d'imputation. */
    public record AllocationHistory(UUID receivableId, LocalDate valueDate, Money amount) {}

    /**
     * Creances susceptibles de composer l'assiette des interets de retard.
     *
     * <p>Les creances de retard elles-memes sont exclues : leur faire porter interet serait de
     * l'anatocisme, et l'exclusion est structurelle plutot que parametree.
     */
    public static List<OverdueLine> overdueLines(Connection c, UUID contractId,
                                                 CurrencyRef currency) {
        List<OverdueLine> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, schedule_id, instalment_number, category, due_date, original_amount"
            + "  FROM loan_receivable"
            + " WHERE contract_id = ? AND NOT cancelled"
            + "   AND category IN ('PRINCIPAL','INTEREST','FEES_AND_INSURANCE')"
            + " ORDER BY due_date, instalment_number, category")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new OverdueLine(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        DueCategory.valueOf(rs.getString(4)), rs.getObject(5, LocalDate.class),
                        Money.of(rs.getBigDecimal(6), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des creances du contrat " + contractId, e);
        }
        return lines;
    }

    /**
     * Historique des imputations, avec la date de valeur du reglement qui les a produites.
     *
     * <p>C'est ce qui permet de reconstituer l'assiette telle qu'elle etait chaque jour, plutot
     * que de l'estimer sur l'etat courant. Un rattrapage de plusieurs jours calculerait sinon tous
     * ses interets de retard sur l'assiette d'aujourd'hui, et un reglement intervenu entre-temps
     * serait ignore.
     */
    public static List<AllocationHistory> allocationHistory(Connection c, UUID contractId,
                                                            CurrencyRef currency) {
        List<AllocationHistory> history = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.receivable_id, p.value_date, a.amount"
            + "  FROM loan_payment_allocation a JOIN loan_payment p ON p.id = a.payment_id"
            + " WHERE p.contract_id = ? ORDER BY p.value_date")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(new AllocationHistory(
                        rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        Money.of(rs.getBigDecimal(3), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des imputations du contrat " + contractId, e);
        }
        return history;
    }

    /** Etat du cumul d'interets de retard d'un contrat. */
    public record LateState(LocalDate through, Money cumulativePrecise, Money posted) {}

    public static Optional<LateState> lateState(Connection c, UUID contractId,
                                                CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT MAX(accrual_date),"
            + "       (SELECT cumulative_precise FROM loan_late_accrual"
            + "         WHERE contract_id = ? AND status = 'ACTIVE'"
            + "         ORDER BY accrual_date DESC LIMIT 1),"
            + "       COALESCE(SUM(posted_delta), 0)"
            + "  FROM loan_late_accrual WHERE contract_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, contractId);
            ps.setObject(2, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                LocalDate through = rs.getObject(1, LocalDate.class);
                if (through == null) {
                    return Optional.empty();
                }
                return Optional.of(new LateState(through,
                    Money.of(rs.getBigDecimal(2), currency),
                    Money.of(rs.getBigDecimal(3), currency)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du cumul de retard du contrat " + contractId, e);
        }
    }

    /** Journee d'interet de retard, conservee pour l'explicabilite comme un interet couru. */
    public record LateAccrual(LocalDate date, Money basis, BigDecimal ratePercent,
                              BigDecimal yearFraction, Money precise, Money cumulative) {}

    public static void insertLateAccruals(Connection c, UUID contractId, List<LateAccrual> days,
                                          Money postedDelta, UUID entryId, LocalDate bookingDate,
                                          UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_late_accrual(id, contract_id, accrual_date, basis_amount,"
            + " annual_rate_percent, year_fraction, precise_amount, cumulative_precise,"
            + " posted_delta, entry_id, booking_date, batch_run_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int index = 0; index < days.size(); index++) {
                LateAccrual day = days.get(index);
                boolean last = index == days.size() - 1;
                ps.setObject(1, Ids.newId());
                ps.setObject(2, contractId);
                ps.setObject(3, day.date());
                ps.setBigDecimal(4, day.basis().amount());
                ps.setBigDecimal(5, day.ratePercent());
                ps.setBigDecimal(6, day.yearFraction());
                ps.setBigDecimal(7, day.precise().amount());
                ps.setBigDecimal(8, day.cumulative().amount());
                // L'ecriture porte sur la derniere journee du lot : c'est elle qui solde l'ecart
                // entre le cumul arrondi et ce qui etait deja impute.
                ps.setBigDecimal(9, last ? postedDelta.amount() : BigDecimal.ZERO);
                ps.setObject(10, last ? entryId : null);
                ps.setObject(11, last ? bookingDate : null);
                ps.setObject(12, batchRunId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des journees de retard", e);
        }
    }

    /** Creance ouverte d'une nature donnee pour une echeance, absente si elle n'existe pas. */
    public static Optional<UUID> findReceivable(Connection c, UUID contractId, UUID scheduleId,
                                                int instalmentNumber, DueCategory category) {
        try (PreparedStatement ps = c.prepareStatement(
            // IS NOT DISTINCT FROM plutot qu'une egalite : l'interet de retard ne se rattache a
            // aucune echeance, et son schedule_id est nul. Le transtypage explicite est requis —
            // sans lui, PostgreSQL ne peut pas inferer le type d'un parametre nul.
            "SELECT id FROM loan_receivable WHERE contract_id = ? AND category = ?"
            + " AND NOT cancelled"
            + " AND schedule_id IS NOT DISTINCT FROM CAST(? AS uuid)"
            + " AND instalment_number = ?")) {
            ps.setObject(1, contractId);
            ps.setString(2, category.name());
            ps.setObject(3, scheduleId);
            ps.setInt(4, instalmentNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche de la creance " + category, e);
        }
    }

    /**
     * Fait courir la creance d'interet de retard du montant accru.
     *
     * <p>Le montant du et le solde progressent du meme pas : le declencheur de la base le verifie,
     * pour qu'une part deja reglee ne redevienne jamais due a la faveur d'un accrual.
     */
    public static void accrueLateInterest(Connection c, UUID receivableId, Money accrued) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_receivable"
            + "   SET original_amount = original_amount + ?, outstanding = outstanding + ?,"
            + "       settled_on = NULL"
            + " WHERE id = ? AND NOT cancelled")) {
            ps.setBigDecimal(1, accrued.amount());
            ps.setBigDecimal(2, accrued.amount());
            ps.setObject(3, receivableId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Creance d'interet de retard " + receivableId + " introuvable ou annulee.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Accrual sur la creance " + receivableId, e);
        }
    }

    /**
     * Reprend l'interet de retard impute par un traitement annule, contrat par contrat.
     *
     * <p>Seul chemin du code qui diminue une creance d'interet de retard, et il n'est emprunte
     * qu'apres contre-passation des ecritures correspondantes. Sans lui, l'annulation laisserait
     * la creance gonflee d'un montant dont plus aucune ecriture ne rend compte — et la
     * reconciliation ne le verrait pas, puisqu'elle ne porte que sur le journal.
     *
     * <p>Une reprise portant sur un interet de retard deja encaisse rendrait le solde negatif et
     * se heurte a la contrainte de la base. C'est le comportement voulu : annuler un traitement
     * dont les produits ont ete encaisses demande un remboursement, pas une reecriture.
     */
    public static void reverseLateInterest(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
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
            ps.setObject(1, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Reprise des interets de retard du traitement " + batchRunId, e);
        }
    }

    /** Neutralise les journees de retard produites par un traitement annule. */
    public static int cancelLateAccruals(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_late_accrual SET status = 'REVERSED'"
            + " WHERE batch_run_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des journees de retard", e);
        }
    }

    // ------------------------------------------------------------------ risque

    public static void assignCustomer(Connection c, UUID contractId, UUID customerId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_contract SET customer_id = ? WHERE id = ?")) {
            ps.setObject(1, customerId);
            ps.setObject(2, contractId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rattachement du contrat " + contractId, e);
        }
    }

    /** Titulaires des contrats, pour la contagion. Absent lorsque le contrat n'est pas rattache. */
    public static Map<UUID, UUID> customersOf(Connection c, Collection<UUID> contractIds) {
        Map<UUID, UUID> customers = new LinkedHashMap<>();
        if (contractIds.isEmpty()) {
            return customers;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, customer_id FROM loan_contract"
            + " WHERE id = ANY (?) AND customer_id IS NOT NULL")) {
            ps.setArray(1, c.createArrayOf("uuid", contractIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    customers.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des titulaires des credits", e);
        }
        return customers;
    }

    /**
     * Encours porte par la banque sur un credit.
     *
     * <p>Capital restant du — le solde du compte de pret — augmente des creances accessoires
     * encore ouvertes. Le capital echu n'y est pas compte deux fois : il figure toujours au compte
     * de pret, dont il ne sort qu'au reglement.
     */
    public static Money exposureOf(Connection c, LoanContract contract) {
        Money principal = io.corebanking.ledger.store.Balances.current(c,
                                                                       contract.loanAccountId());
        Money accessories = Money.zero(contract.currency());
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(outstanding), 0) FROM loan_receivable"
            + " WHERE contract_id = ? AND NOT cancelled AND category <> 'PRINCIPAL'")) {
            ps.setObject(1, contract.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                accessories = Money.of(rs.getBigDecimal(1), contract.currency());
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Calcul de l'encours du contrat " + contract.id(), e);
        }
        return principal.plus(accessories);
    }

    /** Derniere classification active d'un credit. */
    public record ClassificationState(LocalDate on, String bucketCode, int ordinal,
                                      boolean suspended, Money provisioned) {}

    public static Optional<ClassificationState> lastClassification(Connection c, UUID contractId,
                                                                   CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT classified_on, bucket_code, bucket_ordinal, suspended, provision_amount"
            + "  FROM loan_classification WHERE contract_id = ? AND status = 'ACTIVE'"
            + " ORDER BY classified_on DESC LIMIT 1")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ClassificationState(
                    rs.getObject(1, LocalDate.class), rs.getString(2), rs.getInt(3),
                    rs.getBoolean(4), Money.of(rs.getBigDecimal(5), currency)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la classification du contrat " + contractId,
                                           e);
        }
    }

    /** Vrai si le credit est sous suspension d'interets a la date consideree. */
    public static boolean isSuspended(Connection c, UUID contractId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT suspended FROM loan_classification WHERE contract_id = ? AND status = 'ACTIVE'"
            + " ORDER BY classified_on DESC LIMIT 1")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la suspension du contrat " + contractId, e);
        }
    }

    /**
     * Interets constates en produits et encore impayes : l'assiette de la suspension.
     *
     * <p>Les deux composantes sont rendues separement parce qu'elles ont ete constatees sur deux
     * comptes de produits differents, et que la reprise doit viser le compte d'origine de chacune.
     */
    public record RecognisedInterest(Money contractual, Money late) {

        public Money total() {
            return contractual.plus(late);
        }

        public boolean isPositive() {
            return total().isPositive();
        }
    }

    public static RecognisedInterest unpaidRecognisedInterest(Connection c, UUID contractId,
                                                              CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT category, COALESCE(SUM(outstanding), 0) FROM loan_receivable"
            + " WHERE contract_id = ? AND NOT cancelled"
            + "   AND category IN ('INTEREST','LATE_INTEREST') GROUP BY category")) {
            ps.setObject(1, contractId);
            Money contractual = Money.zero(currency);
            Money late = Money.zero(currency);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Money amount = Money.of(rs.getBigDecimal(2), currency);
                    if (DueCategory.valueOf(rs.getString(1)) == DueCategory.LATE_INTEREST) {
                        late = amount;
                    } else {
                        contractual = amount;
                    }
                }
            }
            return new RecognisedInterest(contractual, late);
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des interets impayes du contrat " + contractId,
                                           e);
        }
    }

    /** Classification arretee pour un credit a une date. */
    public record ClassificationRow(
        UUID contractId, LocalDate on, long daysPastDue, String bucketCode, int ordinal,
        boolean performing, String reason, Money exposure, Money collateral, Money base,
        BigDecimal ratePercent, Money provision, Money postedDelta, boolean suspended,
        Money suspendedInterest, UUID entryId, UUID batchRunId) {}

    public static UUID recordClassification(Connection c, ClassificationRow row) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_classification(id, contract_id, classified_on, days_past_due,"
            + " bucket_code, bucket_ordinal, performing, reason, exposure, collateral,"
            + " provision_base, provision_rate_percent, provision_amount, posted_delta, suspended,"
            + " suspended_interest, entry_id, batch_run_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, row.contractId());
            ps.setObject(3, row.on());
            ps.setLong(4, row.daysPastDue());
            ps.setString(5, row.bucketCode());
            ps.setInt(6, row.ordinal());
            ps.setBoolean(7, row.performing());
            ps.setString(8, row.reason());
            ps.setBigDecimal(9, row.exposure().amount());
            ps.setBigDecimal(10, row.collateral().amount());
            ps.setBigDecimal(11, row.base().amount());
            ps.setBigDecimal(12, row.ratePercent());
            ps.setBigDecimal(13, row.provision().amount());
            ps.setBigDecimal(14, row.postedDelta().amount());
            ps.setBoolean(15, row.suspended());
            ps.setBigDecimal(16, row.suspendedInterest().amount());
            ps.setObject(17, row.entryId());
            ps.setObject(18, row.batchRunId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Enregistrement de la classification du contrat " + row.contractId(), e);
        }
        return id;
    }

    /** Neutralise les classifications produites par un traitement annule. */
    public static int cancelClassifications(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_classification SET status = 'REVERSED'"
            + " WHERE batch_run_id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Neutralisation des classifications", e);
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
}
