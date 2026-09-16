package io.corebanking.loan.service;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.Instalment;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.party.Parties;
import io.corebanking.party.Party;
import io.corebanking.party.PartyService;
import io.corebanking.product.ProductCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Origination : la demande, l'instruction, la decision, les conditions.
 *
 * <p>Le socle portait le contrat et son deblocage — la fin de l'histoire. Ce qui manquait est ce
 * que regarde le controle interne : qui a demande quoi, sur quels revenus, qui a decide, dans
 * quelle limite, et ce qui restait a fournir avant que l'argent sorte.
 *
 * <p>Quatre partis pris.
 *
 * <p><b>Les engagements ne se declarent pas, ils se lisent.</b> Le taux d'endettement se calcule
 * sur les echeanciers en vigueur du client, pas sur ce qu'il veut bien dire : un emprunteur oublie
 * rarement ses revenus et souvent ses dettes. La charge mensuelle d'un credit est ce qu'il appelle
 * sur les douze mois a venir, ramene au nombre de mois qu'il couvre — une formule qui vaut pour
 * une mensualite comme pour une echeance trimestrielle ou un credit qui s'eteint dans trois mois.
 *
 * <p><b>La politique ne refuse pas, elle nomme.</b> Un dossier hors politique reste decidable,
 * mais la derogation doit etre ecrite. Refuser automatiquement produit deux effets connus : des
 * dossiers montes juste sous le seuil, et des derogations prises hors du systeme.
 *
 * <p><b>La decision se recalcule sur ce qu'elle accorde.</b> L'instruction se fait au taux propose ;
 * si le decideur accorde autre chose — moins, plus longtemps, plus cher —, les depassements sont
 * recalcules sur les conditions accordees. Un dossier instruit a 8 % et accorde a 14 % n'a pas le
 * meme taux d'endettement, et c'est celui qu'on accorde qui engage l'emprunteur.
 *
 * <p><b>Une condition suspensive retient le versement, pas la signature.</b> Elle ne suspend pas le
 * contrat : elle suspend l'obligation de la banque de verser. C'est donc le deblocage qu'elle
 * retient, et {@link LoanService#disburse} la verifie.
 */
public final class LoanOrigination {

    private LoanOrigination() {}

    /** Fenetre sur laquelle la charge mensuelle d'un engagement se mesure. */
    private static final int WINDOW_MONTHS = 12;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public enum Status { SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, CANCELLED, CONTRACTED, EXPIRED }

    public enum Outcome { APPROVED, REJECTED }

    /**
     * Une condition <b>suspensive</b> retient le versement ; une condition <b>de suivi</b> reste au
     * dossier sans rien retenir — une attestation annuelle, un ratio a maintenir.
     */
    public enum ConditionKind { PRECEDENT, SUBSEQUENT }

    public record Application(UUID id, UUID legalEntityId, UUID branchId, String reference,
                              UUID customerId, String customerReference, String productCode,
                              CurrencyRef currency, Money requestedAmount, int requestedTermMonths,
                              String purpose, LocalDate requestedOn, Status status, UUID contractId,
                              LocalDate closedOn, String closingReason, UUID createdBy) {

        public boolean decidable() {
            return status == Status.SUBMITTED || status == Status.UNDER_REVIEW;
        }
    }

    public record Request(UUID legalEntityId, UUID branchId, String reference, UUID customerId,
                          String productCode, Money requestedAmount, int requestedTermMonths,
                          String purpose, LocalDate requestedOn, UUID createdBy) {
        public Request {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(productCode, "productCode");
            Objects.requireNonNull(requestedAmount, "requestedAmount");
            Objects.requireNonNull(requestedOn, "requestedOn");
            Objects.requireNonNull(createdBy, "createdBy");
            if (!requestedAmount.isPositive()) {
                throw new IllegalArgumentException("Le montant demande est positif : "
                                                   + requestedAmount);
            }
            if (requestedTermMonths <= 0) {
                throw new IllegalArgumentException("La duree demandee se compte en mois : "
                                                   + requestedTermMonths);
            }
        }
    }

    public record Assessment(UUID id, UUID applicationId, Money monthlyIncome, Money monthlyCharges,
                             Money existingCommitments, Money requestedInstalment,
                             BigDecimal debtServiceRatioPercent, Money downPayment,
                             Integer externalScore, String scoreSource, List<String> breaches,
                             LocalDate assessedOn, UUID assessedBy) {

        public Assessment {
            breaches = breaches == null ? List.of() : List.copyOf(breaches);
        }

        public boolean withinPolicy() {
            return breaches.isEmpty();
        }
    }

    /** L'instruction telle qu'elle se saisit : le reste se lit ou se calcule. */
    public record Instruction(UUID applicationId, Money monthlyIncome, Money monthlyCharges,
                              Money downPayment, BigDecimal ratePercent, Integer externalScore,
                              String scoreSource, LocalDate assessedOn, UUID actorId) {
        public Instruction {
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(monthlyIncome, "monthlyIncome");
            Objects.requireNonNull(assessedOn, "assessedOn");
            Objects.requireNonNull(actorId, "actorId");
            if (!monthlyIncome.isPositive()) {
                throw new IllegalArgumentException(
                    "Un dossier s'instruit sur des revenus : " + monthlyIncome);
            }
            if (ratePercent == null || ratePercent.signum() < 0) {
                throw new IllegalArgumentException("Le taux d'instruction est celui qu'on propose "
                    + "a l'emprunteur : sans lui, aucune mensualite ne se simule");
            }
            if (externalScore != null && (scoreSource == null || scoreSource.isBlank())) {
                throw new IllegalArgumentException(
                    "Un score sans source ne se defend pas : nommer l'organisme qui l'a rendu");
            }
        }
    }

    public record Decision(UUID id, UUID applicationId, Outcome outcome, Money grantedAmount,
                           Integer grantedTermMonths, BigDecimal grantedRatePercent,
                           LocalDate decidedOn, LocalDate validUntil, String reason,
                           String waiverReason, UUID decidedBy, UUID approvedBy) {

        public boolean expiredOn(LocalDate on) {
            return validUntil != null && validUntil.isBefore(on);
        }
    }

    /** La decision telle qu'elle se prend. */
    public record Verdict(UUID applicationId, Outcome outcome, Money grantedAmount,
                          Integer grantedTermMonths, BigDecimal grantedRatePercent,
                          LocalDate decidedOn, String reason, String waiverReason, UUID actorId,
                          UUID approverId) {
        public Verdict {
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(decidedOn, "decidedOn");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Une decision de credit porte son motif : "
                    + "c'est ce que lira l'emprunteur, et le controle apres lui");
            }
            if (actorId == null || approverId == null || approverId.equals(actorId)) {
                throw new IllegalArgumentException("Une decision de credit se prend a deux : "
                    + "le demandeur ne peut pas etre le valideur");
            }
            if (outcome == Outcome.APPROVED) {
                if (grantedAmount == null || !grantedAmount.isPositive()) {
                    throw new IllegalArgumentException(
                        "Un accord porte un montant accorde : " + grantedAmount);
                }
                if (grantedTermMonths == null || grantedTermMonths <= 0) {
                    throw new IllegalArgumentException(
                        "Un accord porte une duree accordee : " + grantedTermMonths);
                }
                if (grantedRatePercent == null || grantedRatePercent.signum() < 0) {
                    throw new IllegalArgumentException("Un accord porte le taux accorde : c'est "
                        + "lui que l'echeancier devra appliquer au deblocage");
                }
            }
        }
    }

    public record Condition(UUID id, UUID applicationId, ConditionKind kind, String description,
                            LocalDate dueOn, LocalDate clearedOn, UUID clearedBy,
                            UUID clearedApprovedBy, String evidence, UUID createdBy) {

        public boolean open() {
            return clearedOn == null;
        }
    }

    /** Dossier refuse a l'origination. */
    public static class ApplicationStateException extends RuntimeException {
        public ApplicationStateException(String message) {
            super(message);
        }

        public ApplicationStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ la demande

    /**
     * Depose une demande.
     *
     * <p>Le dossier client est confronte a sa politique de diligence ici, pas au deblocage : une
     * banque qui instruit un dossier pendant trois semaines pour decouvrir au versement qu'il
     * manque un justificatif a perdu trois semaines, et le client avec elle.
     */
    public static Application submit(Connection c, Request request) {
        Party customer = Parties.require(c, request.customerId());
        if (!customer.legalEntityId().equals(request.legalEntityId())) {
            throw new IllegalArgumentException("Le tiers " + customer.reference()
                                               + " releve d'une autre entite juridique");
        }
        PartyService.requireOnboardable(c, request.customerId());
        CurrencyRef currency = currencyOf(c, request.requestedAmount(), request.legalEntityId(),
                                          request.productCode(), request.reference());
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_application(id, legal_entity_id, branch_id, reference, customer_id,"
            + " product_code, currency, requested_amount, requested_term_months, purpose,"
            + " requested_on, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, request.legalEntityId());
            ps.setObject(3, request.branchId());
            ps.setString(4, request.reference());
            ps.setObject(5, request.customerId());
            ps.setString(6, request.productCode());
            ps.setString(7, currency.code());
            ps.setBigDecimal(8, request.requestedAmount().amount());
            ps.setInt(9, request.requestedTermMonths());
            ps.setString(10, request.purpose());
            ps.setObject(11, request.requestedOn());
            ps.setObject(12, request.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Une demande porte deja la reference "
                                                + request.reference(), e);
            }
            throw new LedgerStoreException("Depot de la demande de credit", e);
        }
        event(c, id, "SUBMITTED", request.requestedOn(), request.createdBy(), null,
              request.requestedAmount() + " sur " + request.requestedTermMonths() + " mois", null);
        return require(c, id);
    }

    /** Retire une demande : avant contractualisation, et la trace reste. */
    public static void cancel(Connection c, UUID applicationId, LocalDate on, String reason,
                              UUID actorId) {
        Application application = require(c, applicationId);
        if (application.status() == Status.CONTRACTED) {
            throw new ApplicationStateException("La demande " + application.reference()
                + " a produit le contrat " + application.contractId() + " : elle ne se retire plus");
        }
        if (application.closedOn() != null) {
            throw new ApplicationStateException("Demande deja close le " + application.closedOn()
                                                + " : " + application.status());
        }
        close(c, applicationId, Status.CANCELLED, on, reason);
        event(c, applicationId, "CANCELLED", on, actorId, null, reason, null);
    }

    // ------------------------------------------------------------------ l'instruction

    /**
     * Instruit le dossier : charge existante lue dans les echeanciers, mensualite simulee par le
     * meme moteur que celui qui editera l'echeancier, taux d'endettement, depassements nommes.
     */
    public static Assessment assess(Connection c, Instruction instruction) {
        Application application = require(c, instruction.applicationId());
        if (!application.decidable()) {
            throw new ApplicationStateException("Une demande " + application.status()
                + " ne s'instruit plus : " + application.reference());
        }
        Money charges = instruction.monthlyCharges() == null
            ? Money.zero(application.currency()) : instruction.monthlyCharges();
        Money downPayment = instruction.downPayment() == null
            ? Money.zero(application.currency()) : instruction.downPayment();
        Money existing = monthlyCommitments(c, application.customerId(), application.currency(),
                                            instruction.assessedOn());
        Money instalment = monthlyEquivalent(
            simulate(application.requestedAmount(), application.requestedTermMonths(),
                     instruction.ratePercent(), instruction.assessedOn()).instalments(),
            application.currency(), instruction.assessedOn());
        BigDecimal ratio = ratio(instruction.monthlyIncome(), charges, existing, instalment);
        List<String> breaches = breaches(c, application, application.requestedAmount(),
                                         application.requestedTermMonths(), ratio, downPayment,
                                         instruction.assessedOn());

        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_assessment(id, application_id, monthly_income, monthly_charges,"
            + " existing_commitments, requested_instalment, debt_service_ratio_percent,"
            + " down_payment, external_score, score_source, breaches, assessed_on, assessed_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, application.id());
            ps.setBigDecimal(3, instruction.monthlyIncome().amount());
            ps.setBigDecimal(4, charges.amount());
            ps.setBigDecimal(5, existing.amount());
            ps.setBigDecimal(6, instalment.amount());
            ps.setBigDecimal(7, ratio);
            ps.setBigDecimal(8, downPayment.amount());
            if (instruction.externalScore() == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, instruction.externalScore());
            }
            ps.setString(10, instruction.scoreSource());
            ps.setString(11, breaches.isEmpty() ? null : String.join(" ; ", breaches));
            ps.setObject(12, instruction.assessedOn());
            ps.setObject(13, instruction.actorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Instruction de la demande", e);
        }
        setStatus(c, application.id(), Status.UNDER_REVIEW);
        event(c, application.id(), "ASSESSED", instruction.assessedOn(), instruction.actorId(),
              null, "endettement " + ratio.toPlainString() + " %"
              + (breaches.isEmpty() ? "" : " — " + String.join(" ; ", breaches)), null);
        return requireAssessment(c, id);
    }

    // ------------------------------------------------------------------ la decision

    /**
     * Decide, a deux.
     *
     * <p>Les depassements sont recalcules sur les conditions accordees, pas reprises de
     * l'instruction : c'est ce qu'on accorde qui engage l'emprunteur. Un dossier hors politique
     * exige une derogation ecrite — sans elle, la decision est refusee.
     */
    public static Decision decide(Connection c, Verdict verdict) {
        Application application = require(c, verdict.applicationId());
        if (!application.decidable()) {
            throw new ApplicationStateException("Une demande " + application.status()
                + " ne se decide plus : " + application.reference());
        }
        Assessment assessment = lastAssessment(c, application.id()).orElseThrow(
            () -> new ApplicationStateException("La demande " + application.reference()
                + " n'est pas instruite : on ne decide pas d'un dossier qu'on n'a pas regarde"));

        LocalDate validUntil = null;
        String breachText = null;
        if (verdict.outcome() == Outcome.APPROVED) {
            if (verdict.grantedAmount().isGreaterThan(application.requestedAmount())) {
                throw new IllegalArgumentException("On n'accorde pas plus que demande : "
                    + verdict.grantedAmount() + " pour une demande de "
                    + application.requestedAmount());
            }
            if (!verdict.grantedAmount().currency().equals(application.currency())) {
                throw new IllegalArgumentException("La demande est en " + application.currency().code()
                    + " : " + verdict.grantedAmount());
            }
            Money instalment = monthlyEquivalent(
                simulate(verdict.grantedAmount(), verdict.grantedTermMonths(),
                         verdict.grantedRatePercent(), verdict.decidedOn()).instalments(),
                application.currency(), verdict.decidedOn());
            BigDecimal ratio = ratio(assessment.monthlyIncome(), assessment.monthlyCharges(),
                                     assessment.existingCommitments(), instalment);
            List<String> breaches = breaches(c, application, verdict.grantedAmount(),
                                             verdict.grantedTermMonths(), ratio,
                                             assessment.downPayment(), verdict.decidedOn());
            if (!breaches.isEmpty()
                && (verdict.waiverReason() == null || verdict.waiverReason().isBlank())) {
                throw new IllegalArgumentException("Dossier hors politique d'octroi — "
                    + String.join(" ; ", breaches)
                    + ". Une derogation se decide, et elle s'ecrit : motiver ou revoir les "
                    + "conditions accordees");
            }
            breachText = breaches.isEmpty() ? null : String.join(" ; ", breaches);
            validUntil = verdict.decidedOn().plusDays(validityDays(c, application,
                                                                   verdict.decidedOn()));
        }

        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_application_decision(id, application_id, outcome, granted_amount,"
            + " granted_term_months, granted_rate_percent, decided_on, valid_until, reason,"
            + " waiver_reason, decided_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, application.id());
            ps.setString(3, verdict.outcome().name());
            ps.setBigDecimal(4, verdict.grantedAmount() == null ? null
                                : verdict.grantedAmount().amount());
            if (verdict.grantedTermMonths() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, verdict.grantedTermMonths());
            }
            ps.setBigDecimal(6, verdict.grantedRatePercent());
            ps.setObject(7, verdict.decidedOn());
            ps.setObject(8, validUntil);
            ps.setString(9, verdict.reason());
            ps.setString(10, verdict.waiverReason());
            ps.setObject(11, verdict.actorId());
            ps.setObject(12, verdict.approverId());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new ApplicationStateException("La demande " + application.reference()
                    + " porte deja une decision : la refaire suppose de reinstruire le dossier", e);
            }
            throw new LedgerStoreException("Decision sur la demande de credit", e);
        }
        if (verdict.outcome() == Outcome.APPROVED) {
            setStatus(c, application.id(), Status.APPROVED);
        } else {
            close(c, application.id(), Status.REJECTED, verdict.decidedOn(), verdict.reason());
        }
        event(c, application.id(), "DECIDED", verdict.decidedOn(), verdict.actorId(),
              verdict.approverId(),
              verdict.outcome() + (verdict.grantedAmount() == null ? ""
                  : " " + verdict.grantedAmount() + " sur " + verdict.grantedTermMonths()
                    + " mois a " + verdict.grantedRatePercent() + " %")
              + (breachText == null ? "" : " — derogation : " + verdict.waiverReason()),
              null);
        return requireDecision(c, application.id());
    }

    // ------------------------------------------------------------------ les conditions

    public static Condition addCondition(Connection c, UUID applicationId, ConditionKind kind,
                                         String description, LocalDate dueOn, LocalDate on,
                                         UUID actorId) {
        Application application = require(c, applicationId);
        if (application.status() == Status.CANCELLED || application.status() == Status.REJECTED
            || application.status() == Status.EXPIRED) {
            throw new ApplicationStateException("Une demande " + application.status()
                + " ne recoit plus de condition : " + application.reference());
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Une condition dit ce qu'elle attend");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_condition(id, application_id, kind, description, due_on, created_by)"
            + " VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, applicationId);
            ps.setString(3, kind.name());
            ps.setString(4, description);
            ps.setObject(5, dueOn);
            ps.setObject(6, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Ajout d'une condition", e);
        }
        event(c, applicationId, "CONDITION_ADDED", on, actorId, null, kind + " : " + description,
              null);
        return requireCondition(c, id);
    }

    /** Leve une condition, a deux : c'est le geste qui ouvre le versement. */
    public static Condition clearCondition(Connection c, UUID conditionId, LocalDate on,
                                           String evidence, UUID actorId, UUID approverId) {
        if (approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException("La levee d'une condition se constate a deux : "
                + "elle ouvre un versement");
        }
        Condition condition = requireCondition(c, conditionId);
        if (!condition.open()) {
            throw new ApplicationStateException("Condition deja levee le " + condition.clearedOn());
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_condition SET cleared_on = ?, cleared_by = ?, cleared_approved_by = ?,"
            + " evidence = ? WHERE id = ? AND cleared_on IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, actorId);
            ps.setObject(3, approverId);
            ps.setString(4, evidence);
            ps.setObject(5, conditionId);
            if (ps.executeUpdate() == 0) {
                throw new ApplicationStateException("Condition deja levee : " + conditionId);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Levee d'une condition", e);
        }
        event(c, condition.applicationId(), "CONDITION_CLEARED", on, actorId, approverId,
              condition.description() + (evidence == null ? "" : " — " + evidence), null);
        return requireCondition(c, conditionId);
    }

    /**
     * Refuse le versement tant qu'une condition suspensive du dossier n'est pas levee.
     *
     * <p>Appelee par le deblocage. Un contrat sans dossier d'origination — une reprise, une saisie
     * directe — n'a rien a lever : le controle ne s'invente pas de conditions.
     */
    public static void requireConditionsCleared(Connection c, UUID contractId) {
        List<String> open = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT k.description FROM loan_condition k"
            + "  JOIN loan_application a ON a.id = k.application_id"
            + " WHERE a.contract_id = ? AND k.kind = 'PRECEDENT' AND k.cleared_on IS NULL"
            + " ORDER BY k.created_at")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Conditions suspensives du contrat", e);
        }
        if (!open.isEmpty()) {
            throw new ApplicationStateException("Conditions suspensives non levees : "
                + String.join(" ; ", open)
                + ". Une condition suspensive ne retient pas la signature, elle retient le "
                + "versement.");
        }
    }

    /**
     * Confronte l'echeancier du deblocage aux conditions accordees.
     *
     * <p>Sans ce controle, la decision du comite est decorative : rien n'empecherait de debloquer
     * a 18 % un credit accorde a 9 %, ni sur dix ans un credit accorde sur trois.
     */
    public static void requireGrantedTerms(Connection c, UUID contractId, BigDecimal ratePercent,
                                           LocalDate disbursedOn, LocalDate lastDueDate) {
        Optional<Decision> decision = decisionOfContract(c, contractId);
        if (decision.isEmpty()) {
            return;
        }
        Decision granted = decision.get();
        if (granted.grantedRatePercent() != null && ratePercent != null
            && granted.grantedRatePercent().compareTo(ratePercent) != 0) {
            throw new ApplicationStateException("Taux accorde "
                + granted.grantedRatePercent().toPlainString() + " %, echeancier a "
                + ratePercent.toPlainString() + " % : le deblocage applique ce qui a ete decide, "
                + "ou la decision est a reprendre");
        }
        if (granted.grantedTermMonths() != null && lastDueDate != null && disbursedOn != null) {
            long months = ChronoUnit.MONTHS.between(disbursedOn.withDayOfMonth(1),
                                                    lastDueDate.withDayOfMonth(1));
            if (lastDueDate.getDayOfMonth() > disbursedOn.getDayOfMonth()) {
                months += 1;
            }
            if (months > granted.grantedTermMonths()) {
                throw new ApplicationStateException("Duree accordee "
                    + granted.grantedTermMonths() + " mois, echeancier sur " + months
                    + " mois : le deblocage applique ce qui a ete decide");
            }
        }
    }

    // ------------------------------------------------------------------ la contractualisation

    public record Contracting(UUID applicationId, String contractReference, UUID loanAccountId,
                              UUID settlementAccountId, LocalDate disbursementDate, UUID actorId) {}

    /**
     * Transforme un accord en contrat, une fois.
     *
     * <p>Le contrat prend le montant <b>accorde</b>, jamais le demande, et les conditions du
     * dossier le suivent : ce sont elles qui retiendront le versement.
     */
    public static UUID contractualise(Connection c, Contracting contracting) {
        Application application = require(c, contracting.applicationId());
        if (application.status() != Status.APPROVED) {
            throw new ApplicationStateException("Seul un accord produit un contrat ; la demande "
                + application.reference() + " est " + application.status());
        }
        Decision decision = requireDecision(c, application.id());
        if (decision.expiredOn(contracting.disbursementDate())) {
            throw new ApplicationStateException("Offre expiree le " + decision.validUntil()
                + " : une decision prise sur une situation ancienne n'est plus une decision, "
                + "le dossier se reinstruit");
        }
        UUID contractId = LoanStore.createContract(c, new LoanStore.ContractDraft(
            application.legalEntityId(), contracting.contractReference(), application.productCode(),
            application.currency(), contracting.loanAccountId(), contracting.settlementAccountId(),
            decision.grantedAmount(), contracting.disbursementDate(), contracting.actorId()));
        LoanStore.assignCustomer(c, contractId, application.customerId());
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_application SET status = 'CONTRACTED', contract_id = ?, closed_on = ?"
            + " WHERE id = ? AND status = 'APPROVED'")) {
            ps.setObject(1, contractId);
            ps.setObject(2, contracting.disbursementDate());
            ps.setObject(3, application.id());
            if (ps.executeUpdate() == 0) {
                throw new ApplicationStateException("La demande " + application.reference()
                    + " a change d'etat : contractualisation abandonnee");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Contractualisation de la demande", e);
        }
        event(c, application.id(), "CONTRACTED", contracting.disbursementDate(),
              contracting.actorId(), null,
              contracting.contractReference() + " pour " + decision.grantedAmount(), null);
        return contractId;
    }

    // ------------------------------------------------------------------ l'arrete

    /** Constate les offres perimees : un accord non contractualise a une fin. */
    public static List<String> expire(Connection c, UUID legalEntityId, LocalDate businessDate,
                                      UUID batchRunId, UUID actorId) {
        List<String> expired = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.reference, d.valid_until FROM loan_application a"
            + "  JOIN loan_application_decision d ON d.application_id = a.id"
            + " WHERE a.legal_entity_id = ? AND a.status = 'APPROVED'"
            + "   AND d.valid_until IS NOT NULL AND d.valid_until < ?"
            + " ORDER BY a.reference")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    String reference = rs.getString(2);
                    close(c, id, Status.EXPIRED, businessDate,
                          "offre valable jusqu'au " + rs.getObject(3, LocalDate.class));
                    event(c, id, "EXPIRED", businessDate, actorId, null,
                          "offre valable jusqu'au " + rs.getObject(3, LocalDate.class), batchRunId);
                    expired.add(reference);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Expiration des offres de credit", e);
        }
        return expired;
    }

    /** Rend a l'accord les offres expirees par un arrete annule. */
    public static int cancelRun(Connection c, UUID batchRunId) {
        List<UUID> applications = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT application_id FROM loan_application_event"
            + " WHERE batch_run_id = ? AND kind = 'EXPIRED'")) {
            ps.setObject(1, batchRunId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    applications.add(rs.getObject(1, UUID.class));
                }
            }
            for (UUID id : applications) {
                try (PreparedStatement update = c.prepareStatement(
                    "UPDATE loan_application SET status = 'APPROVED', closed_on = NULL,"
                    + " closing_reason = NULL WHERE id = ? AND status = 'EXPIRED'")) {
                    update.setObject(1, id);
                    update.executeUpdate();
                }
            }
            try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM loan_application_event WHERE batch_run_id = ? AND kind = 'EXPIRED'")) {
                delete.setObject(1, batchRunId);
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des expirations d'offres", e);
        }
        return applications.size();
    }

    // ------------------------------------------------------------------ calcul

    /**
     * Charge mensuelle des engagements en cours d'un client.
     *
     * <p>Par contrat : ce que l'echeancier en vigueur appelle sur les douze mois a venir, divise
     * par le nombre de mois que ces echeances couvrent. Un credit qui s'eteint dans trois mois
     * pese sur trois mois, pas sur douze ; une echeance trimestrielle pese le tiers de son montant.
     */
    public static Money monthlyCommitments(Connection c, UUID customerId, CurrencyRef currency,
                                           LocalDate from) {
        Map<UUID, List<Object[]>> perContract = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT s.contract_id, l.due_date, l.total"
            + "  FROM loan_schedule_line l"
            + "  JOIN loan_schedule s ON s.id = l.schedule_id"
            + "  JOIN loan_contract k ON k.id = s.contract_id"
            + " WHERE k.customer_id = ? AND k.status = 'ACTIVE' AND s.superseded_on IS NULL"
            + "   AND l.due_date > ? AND l.due_date <= ?"
            + " ORDER BY s.contract_id, l.due_date")) {
            ps.setObject(1, customerId);
            ps.setObject(2, from);
            ps.setObject(3, from.plusMonths(WINDOW_MONTHS));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    perContract.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                        .add(new Object[] {rs.getObject(2, LocalDate.class), rs.getBigDecimal(3)});
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Engagements en cours du client", e);
        }
        Money total = Money.zero(currency);
        for (List<Object[]> lines : perContract.values()) {
            BigDecimal due = BigDecimal.ZERO;
            LocalDate last = from;
            for (Object[] line : lines) {
                due = due.add((BigDecimal) line[1]);
                last = (LocalDate) line[0];
            }
            total = total.plus(spread(Money.of(due, currency), from, last));
        }
        return total.roundToCurrency();
    }

    private static Money monthlyEquivalent(List<Instalment> instalments, CurrencyRef currency,
                                           LocalDate from) {
        BigDecimal due = BigDecimal.ZERO;
        LocalDate last = from;
        LocalDate window = from.plusMonths(WINDOW_MONTHS);
        for (Instalment instalment : instalments) {
            if (instalment.dueDate().isAfter(from) && !instalment.dueDate().isAfter(window)) {
                due = due.add(instalment.total().amount());
                last = instalment.dueDate();
            }
        }
        return spread(Money.of(due, currency), from, last).roundToCurrency();
    }

    private static Money spread(Money due, LocalDate from, LocalDate last) {
        long months = Math.max(1, Math.min(WINDOW_MONTHS, ChronoUnit.MONTHS.between(from, last)
                                                          + (last.isAfter(from) ? 1 : 0)));
        return due.dividedBy(BigDecimal.valueOf(months));
    }

    private static AmortisationSchedule simulate(Money amount, int termMonths, BigDecimal rate,
                                                 LocalDate from) {
        LoanTerms terms = LoanTerms.of(amount)
            .disbursedOn(from)
            .firstDueDate(from.plusMonths(1))
            .ratePercent(rate)
            .frequency(Periodicity.MONTHLY)
            .instalments(termMonths)
            .build();
        return ScheduleGenerator.generate(terms);
    }

    private static BigDecimal ratio(Money income, Money charges, Money existing, Money instalment) {
        BigDecimal load = charges.amount().add(existing.amount()).add(instalment.amount());
        return load.multiply(HUNDRED).divide(income.amount(), 2, RoundingMode.HALF_UP);
    }

    /** Les depassements de la politique d'octroi, nommes — jamais un refus. */
    private static List<String> breaches(Connection c, Application application, Money amount,
                                         int termMonths, BigDecimal ratio, Money downPayment,
                                         LocalDate on) {
        Optional<LendingPolicies.Policy> found = LendingPolicies.find(
            c, application.legalEntityId(), application.productCode(), on);
        if (found.isEmpty()) {
            return List.of();
        }
        LendingPolicies.Policy policy = found.get();
        List<String> breaches = new ArrayList<>();
        if (policy.maxDebtServiceRatioPercent() != null
            && ratio.compareTo(policy.maxDebtServiceRatioPercent()) > 0) {
            breaches.add("endettement " + ratio.toPlainString() + " % au-dela de "
                         + policy.maxDebtServiceRatioPercent().toPlainString() + " %");
        }
        if (policy.maxAmount() != null && amount.amount().compareTo(policy.maxAmount()) > 0) {
            breaches.add("montant " + amount + " au-dela de "
                         + policy.maxAmount().toPlainString());
        }
        if (policy.maxTermMonths() != null && termMonths > policy.maxTermMonths()) {
            breaches.add("duree " + termMonths + " mois au-dela de " + policy.maxTermMonths());
        }
        if (policy.minDownPaymentPercent() != null) {
            BigDecimal required = amount.amount().multiply(policy.minDownPaymentPercent())
                .divide(HUNDRED, amount.currency().scale(), RoundingMode.HALF_UP);
            if (downPayment.amount().compareTo(required) < 0) {
                breaches.add("apport " + downPayment + " en deca de "
                             + policy.minDownPaymentPercent().toPlainString() + " % ("
                             + required.toPlainString() + ")");
            }
        }
        if (policy.collateralRequired()) {
            breaches.add("garantie exigee par la politique : a constituer avant deblocage");
        }
        return breaches;
    }

    private static int validityDays(Connection c, Application application, LocalDate on) {
        return LendingPolicies.find(c, application.legalEntityId(), application.productCode(), on)
            .map(LendingPolicies.Policy::decisionValidityDays)
            .orElse(LendingPolicies.DEFAULT_VALIDITY_DAYS);
    }

    private static CurrencyRef currencyOf(Connection c, Money amount, UUID legalEntityId,
                                          String productCode, String reference) {
        ProductCatalog.requireProductCurrency(c, legalEntityId, productCode,
                                              amount.currency().code(), "la demande " + reference);
        return amount.currency();
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT_APPLICATION =
        "SELECT a.id, a.legal_entity_id, a.branch_id, a.reference, a.customer_id, p.reference,"
        + " a.product_code, a.currency, cur.scale, cur.rounding_mode, a.requested_amount,"
        + " a.requested_term_months, a.purpose, a.requested_on, a.status, a.contract_id,"
        + " a.closed_on, a.closing_reason, a.created_by"
        + "  FROM loan_application a JOIN party p ON p.id = a.customer_id"
        + "  JOIN currency cur ON cur.code = a.currency";

    public static Application require(Connection c, UUID id) {
        return find(c, id).orElseThrow(
            () -> new IllegalArgumentException("Demande de credit inconnue : " + id));
    }

    public static Optional<Application> find(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_APPLICATION + " WHERE a.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readApplication(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la demande " + id, e);
        }
    }

    /** Les demandes d'une entite, filtrees par statut si l'appelant en nomme un. */
    public static List<Application> applications(Connection c, UUID legalEntityId, Status status) {
        List<Application> applications = new ArrayList<>();
        String sql = SELECT_APPLICATION + " WHERE a.legal_entity_id = ?"
                     + (status == null ? "" : " AND a.status = ?") + " ORDER BY a.reference";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, legalEntityId);
            if (status != null) {
                ps.setString(2, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    applications.add(readApplication(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des demandes de credit", e);
        }
        return applications;
    }

    private static Application readApplication(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(8), rs.getInt(9),
                                               RoundingMode.valueOf(rs.getString(10)));
        return new Application(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                               rs.getObject(3, UUID.class), rs.getString(4),
                               rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
                               currency, Money.of(rs.getBigDecimal(11), currency), rs.getInt(12),
                               rs.getString(13), rs.getObject(14, LocalDate.class),
                               Status.valueOf(rs.getString(15)), rs.getObject(16, UUID.class),
                               rs.getObject(17, LocalDate.class), rs.getString(18),
                               rs.getObject(19, UUID.class));
    }

    private static final String SELECT_ASSESSMENT =
        "SELECT s.id, s.application_id, a.currency, cur.scale, cur.rounding_mode, s.monthly_income,"
        + " s.monthly_charges, s.existing_commitments, s.requested_instalment,"
        + " s.debt_service_ratio_percent, s.down_payment, s.external_score, s.score_source,"
        + " s.breaches, s.assessed_on, s.assessed_by"
        + "  FROM loan_assessment s JOIN loan_application a ON a.id = s.application_id"
        + "  JOIN currency cur ON cur.code = a.currency";

    public static Assessment requireAssessment(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_ASSESSMENT + " WHERE s.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Instruction inconnue : " + id);
                }
                return readAssessment(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'instruction", e);
        }
    }

    /** Les instructions d'un dossier, la plus recente d'abord : on garde ce qu'on a vu et quand. */
    public static List<Assessment> assessments(Connection c, UUID applicationId) {
        List<Assessment> assessments = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_ASSESSMENT + " WHERE s.application_id = ? ORDER BY s.assessed_on DESC,"
            + " s.created_at DESC")) {
            ps.setObject(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    assessments.add(readAssessment(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des instructions", e);
        }
        return assessments;
    }

    private static Optional<Assessment> lastAssessment(Connection c, UUID applicationId) {
        List<Assessment> assessments = assessments(c, applicationId);
        return assessments.isEmpty() ? Optional.empty() : Optional.of(assessments.get(0));
    }

    private static Assessment readAssessment(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(3), rs.getInt(4),
                                               RoundingMode.valueOf(rs.getString(5)));
        String breaches = rs.getString(14);
        // La base tient cinq decimales pour tous les montants ; une instruction en francs se relit
        // en francs, sinon chaque comparaison porterait sur la precision de stockage.
        return new Assessment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                              Money.of(rs.getBigDecimal(6), currency).roundToCurrency(),
                              Money.of(rs.getBigDecimal(7), currency).roundToCurrency(),
                              Money.of(rs.getBigDecimal(8), currency).roundToCurrency(),
                              Money.of(rs.getBigDecimal(9), currency).roundToCurrency(),
                              rs.getBigDecimal(10),
                              Money.of(rs.getBigDecimal(11), currency).roundToCurrency(),
                              rs.getObject(12, Integer.class), rs.getString(13),
                              breaches == null ? List.of() : List.of(breaches.split(" ; ")),
                              rs.getObject(15, LocalDate.class), rs.getObject(16, UUID.class));
    }

    private static final String SELECT_DECISION =
        "SELECT d.id, d.application_id, d.outcome, d.granted_amount, d.granted_term_months,"
        + " d.granted_rate_percent, d.decided_on, d.valid_until, d.reason, d.waiver_reason,"
        + " d.decided_by, d.approved_by, a.currency, cur.scale, cur.rounding_mode"
        + "  FROM loan_application_decision d JOIN loan_application a ON a.id = d.application_id"
        + "  JOIN currency cur ON cur.code = a.currency";

    public static Decision requireDecision(Connection c, UUID applicationId) {
        return decision(c, applicationId).orElseThrow(
            () -> new ApplicationStateException("Aucune decision sur la demande " + applicationId));
    }

    public static Optional<Decision> decision(Connection c, UUID applicationId) {
        return oneDecision(c, SELECT_DECISION + " WHERE d.application_id = ?", applicationId);
    }

    private static Optional<Decision> decisionOfContract(Connection c, UUID contractId) {
        return oneDecision(c, SELECT_DECISION + " WHERE a.contract_id = ?", contractId);
    }

    private static Optional<Decision> oneDecision(Connection c, String sql, UUID key) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                CurrencyRef currency = new CurrencyRef(rs.getString(13), rs.getInt(14),
                                                       RoundingMode.valueOf(rs.getString(15)));
                BigDecimal granted = rs.getBigDecimal(4);
                return Optional.of(new Decision(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    Outcome.valueOf(rs.getString(3)),
                    granted == null ? null : Money.of(granted, currency),
                    rs.getObject(5, Integer.class), rs.getBigDecimal(6),
                    rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
                    rs.getString(9), rs.getString(10), rs.getObject(11, UUID.class),
                    rs.getObject(12, UUID.class)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la decision", e);
        }
    }

    private static final String SELECT_CONDITION =
        "SELECT id, application_id, kind, description, due_on, cleared_on, cleared_by,"
        + " cleared_approved_by, evidence, created_by FROM loan_condition";

    public static Condition requireCondition(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_CONDITION + " WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Condition inconnue : " + id);
                }
                return readCondition(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la condition", e);
        }
    }

    public static List<Condition> conditions(Connection c, UUID applicationId) {
        List<Condition> conditions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_CONDITION + " WHERE application_id = ? ORDER BY kind, created_at")) {
            ps.setObject(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    conditions.add(readCondition(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des conditions", e);
        }
        return conditions;
    }

    private static Condition readCondition(ResultSet rs) throws SQLException {
        return new Condition(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                             ConditionKind.valueOf(rs.getString(3)), rs.getString(4),
                             rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                             rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                             rs.getString(9), rs.getObject(10, UUID.class));
    }

    // ------------------------------------------------------------------ ecriture interne

    private static void setStatus(Connection c, UUID applicationId, Status status) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_application SET status = ? WHERE id = ? AND status IN"
            + " ('SUBMITTED','UNDER_REVIEW')")) {
            ps.setString(1, status.name());
            ps.setObject(2, applicationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Statut de la demande", e);
        }
    }

    private static void close(Connection c, UUID applicationId, Status status, LocalDate on,
                              String reason) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_application SET status = ?, closed_on = ?, closing_reason = ?"
            + " WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setObject(2, on);
            ps.setString(3, reason);
            ps.setObject(4, applicationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de la demande", e);
        }
    }

    private static void event(Connection c, UUID applicationId, String kind, LocalDate on,
                              UUID actorId, UUID approverId, String detail, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_application_event(application_id, kind, occurred_on, actor_id,"
            + " approver_id, detail, batch_run_id) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, applicationId);
            ps.setString(2, kind);
            ps.setObject(3, on);
            ps.setObject(4, actorId);
            ps.setObject(5, approverId);
            ps.setString(6, detail);
            ps.setObject(7, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Journal du dossier de credit", e);
        }
    }

    /** Le journal du dossier : ce qui lui est arrive, dans l'ordre. */
    public record Event(String kind, LocalDate occurredOn, UUID actorId, UUID approverId,
                        String detail) {}

    public static List<Event> events(Connection c, UUID applicationId) {
        List<Event> events = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind, occurred_on, actor_id, approver_id, detail FROM loan_application_event"
            + " WHERE application_id = ? ORDER BY id")) {
            ps.setObject(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new Event(rs.getString(1), rs.getObject(2, LocalDate.class),
                                         rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                                         rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Journal du dossier", e);
        }
        return events;
    }

    /** Devise de tenue de l'entite — utile aux restitutions qui agregent plusieurs dossiers. */
    public static CurrencyRef functionalCurrency(Connection c, UUID legalEntityId) {
        return Entities.functionalCurrency(c, legalEntityId);
    }
}
