package io.corebanking.deposits;

import io.corebanking.calendar.Calendars;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.AccountBlockedException;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ordres permanents : le virement que le client programme une fois.
 *
 * <p><b>L'ordre est celui du client</b>, pas celui d'un tiers. Il consomme donc ses plafonds —
 * contrairement au cheque, qui est l'instrument d'un porteur, et au prelevement, qui est
 * l'engagement pris envers un creancier.
 *
 * <p><b>Un echec n'est pas une anomalie de la banque.</b> Il se retente un nombre borne de fois,
 * le jour ouvre suivant, puis l'echeance est abandonnee et le calendrier passe a la suivante. La
 * reporter indefiniment ferait partir deux loyers le meme mois, ce qu'aucun client n'a demande.
 *
 * <p><b>Vers l'exterieur, l'ordre permanent ne comptabilise pas lui-meme</b> : il depose un ordre
 * de paiement, qui suivra son cycle — envoi, reglement sur le nostro, retour. Deux chemins pour
 * sortir de l'argent de la banque seraient deux verites sur le meme sujet, et la seconde se
 * decouvrirait au premier rapprochement.
 *
 * <p><b>Un balayage sans rien a balayer n'est pas un echec</b> : c'est une echeance ou il n'y avait
 * rien a faire. Elle ne consomme pas de tentative et ne laisse pas d'impaye.
 */
public final class StandingOrderService {

    private final Database database;
    private final OperationsService operations;
    private final PaymentService payments;

    public StandingOrderService(Database database, PostingService postingService) {
        this.database = database;
        this.operations = new OperationsService(database, postingService);
        this.payments = new PaymentService(database, postingService);
    }

    /** Nom de l'etape d'arrete, porte par les cles d'idempotence. */
    static final String STEP = "STANDING_ORDERS";

    public enum Kind { FIXED, SWEEP }

    public enum Outcome { EXECUTED, REJECTED, SKIPPED }

    public record StandingOrder(UUID id, UUID legalEntityId, UUID accountId, String reference,
                                Kind kind, Money amount, Money floorAmount,
                                UUID beneficiaryAccountId, String beneficiaryName,
                                String beneficiaryBank, String beneficiaryAccount,
                                Periodicity frequency, LocalDate startDate, LocalDate endDate,
                                int occurrence, Integer occurrences, LocalDate dueDate,
                                LocalDate nextAttemptDate, int attempts, int maxAttempts,
                                String status, String narrative, LocalDate cancelledOn,
                                String cancelReason, UUID createdBy, UUID approvedBy) {

        public boolean internal() {
            return beneficiaryAccountId != null;
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    public record Execution(UUID id, UUID standingOrderId, LocalDate dueDate,
                            LocalDate attemptedOn, int attempt, Outcome outcome, Money amount,
                            Money fee, Money tax, String reason, UUID entryId,
                            UUID paymentOrderId) {}

    public record Draft(UUID legalEntityId, UUID accountId, String reference, Kind kind,
                        Money amount, Money floorAmount, UUID beneficiaryAccountId,
                        String beneficiaryName, String beneficiaryBank, String beneficiaryAccount,
                        Periodicity frequency, LocalDate startDate, LocalDate endDate,
                        Integer occurrences, Integer maxAttempts, String narrative, UUID createdBy,
                        UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(startDate, "startDate");
            if (kind == Kind.FIXED && (amount == null || !amount.isPositive())) {
                throw new IllegalArgumentException("Un ordre a montant fixe porte son montant : "
                                                   + amount);
            }
            if (kind == Kind.SWEEP) {
                if (amount != null) {
                    throw new IllegalArgumentException("Un balayage ne porte pas de montant : il "
                        + "vire ce qui depasse le plancher, et ce montant change chaque fois");
                }
                if (floorAmount == null || floorAmount.isNegative()) {
                    throw new IllegalArgumentException("Un balayage porte le plancher a laisser "
                        + "sur le compte : " + floorAmount);
                }
            }
            boolean internal = beneficiaryAccountId != null;
            boolean external = beneficiaryName != null || beneficiaryBank != null
                               || beneficiaryAccount != null;
            if (internal == external) {
                throw new IllegalArgumentException("Un ordre permanent designe un beneficiaire, et "
                    + "un seul : un compte de la banque, ou un tiers d'ailleurs (nom, banque, "
                    + "compte)");
            }
            if (external && (beneficiaryName == null || beneficiaryBank == null
                             || beneficiaryAccount == null)) {
                throw new IllegalArgumentException(
                    "Un beneficiaire d'ailleurs se designe par son nom, sa banque et son compte");
            }
            if (endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("Un ordre permanent ne cesse pas avant de "
                    + "commencer : " + startDate + " a " + endDate);
            }
            if (occurrences != null && occurrences <= 0) {
                throw new IllegalArgumentException("Un nombre d'echeances est positif : "
                                                   + occurrences);
            }
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("Une echeance se tente au moins une fois : "
                                                   + maxAttempts);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Un ordre permanent se met en place a deux : il "
                    + "engage des virements que personne ne redemandera");
            }
        }
    }

    /** Ordre permanent refuse. */
    public static class StandingOrderRefusedException extends RuntimeException {
        public StandingOrderRefusedException(String message) {
            super(message);
        }
    }

    private static final class Rejection extends RuntimeException {
        private final String reason;

        private Rejection(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    // ------------------------------------------------------------------ mise en place

    public StandingOrder register(Draft draft) {
        return database.inTransaction(c -> {
            Account debtor = Accounts.loadAll(c, Set.of(draft.accountId())).get(draft.accountId());
            if (debtor == null || !debtor.legalEntityId().equals(draft.legalEntityId())) {
                throw new IllegalArgumentException("Compte inconnu : " + draft.accountId());
            }
            if (draft.amount() != null && !draft.amount().currency().equals(debtor.currency())) {
                throw new IllegalArgumentException("Le compte est tenu en "
                    + debtor.currency().code() + " : " + draft.amount());
            }
            if (draft.beneficiaryAccountId() != null) {
                Account beneficiary = Accounts.loadAll(c, Set.of(draft.beneficiaryAccountId()))
                    .get(draft.beneficiaryAccountId());
                if (beneficiary == null
                    || !beneficiary.legalEntityId().equals(draft.legalEntityId())) {
                    throw new IllegalArgumentException("Beneficiaire inconnu de cette entite : "
                                                       + draft.beneficiaryAccountId());
                }
                if (!beneficiary.currency().equals(debtor.currency())) {
                    throw new IllegalArgumentException("Un ordre permanent ne change pas de "
                        + "devise : " + debtor.currency().code() + " vers "
                        + beneficiary.currency().code());
                }
            }
            UUID id = Ids.newId();
            LocalDate firstDue = businessDay(c, draft.legalEntityId(), draft.startDate());
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO standing_order(id, legal_entity_id, account_id, reference, kind,"
                + " amount, floor_amount, beneficiary_account_id, beneficiary_name,"
                + " beneficiary_bank, beneficiary_account, frequency, start_date, end_date,"
                + " due_date, next_attempt_date, occurrences, max_attempts, narrative, created_by,"
                + " approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, draft.legalEntityId());
                ps.setObject(3, draft.accountId());
                ps.setString(4, draft.reference());
                ps.setString(5, draft.kind().name());
                ps.setBigDecimal(6, draft.amount() == null ? null : draft.amount().amount());
                ps.setBigDecimal(7, draft.floorAmount() == null ? null
                                    : draft.floorAmount().amount());
                ps.setObject(8, draft.beneficiaryAccountId());
                ps.setString(9, draft.beneficiaryName());
                ps.setString(10, draft.beneficiaryBank());
                ps.setString(11, draft.beneficiaryAccount());
                ps.setString(12, draft.frequency().name());
                ps.setObject(13, draft.startDate());
                ps.setObject(14, draft.endDate());
                ps.setObject(15, firstDue);
                ps.setObject(16, firstDue);
                if (draft.occurrences() == null) {
                    ps.setNull(17, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(17, draft.occurrences());
                }
                ps.setInt(18, draft.maxAttempts() == null ? 3 : draft.maxAttempts());
                ps.setString(19, draft.narrative());
                ps.setObject(20, draft.createdBy());
                ps.setObject(21, draft.approvedBy());
                ps.executeUpdate();
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw new IllegalStateException("Un ordre permanent porte deja la reference "
                                                    + draft.reference(), e);
                }
                throw new LedgerStoreException("Mise en place de l'ordre permanent", e);
            }
            return require(c, id);
        });
    }

    /** Revocation par le client : ce qui a deja ete vire reste vire, rien de plus ne partira. */
    public StandingOrder cancel(UUID id, LocalDate on, String reason, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Une revocation porte son motif");
        }
        return database.inTransaction(c -> {
            StandingOrder order = lock(c, id);
            if (!order.active()) {
                throw new StandingOrderRefusedException("L'ordre permanent " + order.reference()
                    + " est " + order.status() + " : il ne se revoque plus");
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE standing_order SET status = 'CANCELLED', cancelled_on = ?,"
                + " cancel_reason = ? WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setObject(1, on);
                ps.setString(2, reason);
                ps.setObject(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Revocation de l'ordre permanent", e);
            }
            return require(c, id);
        });
    }

    // ------------------------------------------------------------------ execution

    /** Les ordres dont l'echeance est arrivee, dans l'ordre de leur reference. */
    public static List<UUID> due(Connection c, UUID legalEntityId, LocalDate businessDate) {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM standing_order WHERE legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND next_attempt_date <= ? ORDER BY reference")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Ordres permanents echus", e);
        }
        return ids;
    }

    /**
     * Execute une echeance.
     *
     * <p>La tentative se fait sous point de sauvegarde : un refus du ledger ne laisse rien
     * derriere lui, et le rejet s'ecrit avec la transaction qui l'a constate — la meme a
     * l'arrete et au traitement a blanc, qu'une transaction independante trahirait en validant.
     */
    public Execution execute(UUID id, UUID runId, UUID actorId) {
        return database.inTransaction(c -> {
            StandingOrder order = lock(c, id);
            LocalDate on = OperationsService.businessDate(c, order.legalEntityId());
            if (!order.active() || order.nextAttemptDate().isAfter(on)) {
                return null;
            }
            int attempt = order.attempts() + 1;
            Savepoint tentative = savepoint(c);
            try {
                return succeed(c, order, on, attempt, runId, actorId);
            } catch (Rejection r) {
                rollbackTo(c, tentative);
                return fail(c, order, on, attempt, r.reason, runId);
            } catch (InsufficientFundsException e) {
                rollbackTo(c, tentative);
                return fail(c, order, on, attempt, "SANS_PROVISION", runId);
            } catch (AccountBlockedException e) {
                rollbackTo(c, tentative);
                return fail(c, order, on, attempt, "COMPTE_BLOQUE", runId);
            } catch (Limits.LimitExceededException e) {
                rollbackTo(c, tentative);
                return fail(c, order, on, attempt, "PLAFOND_DEPASSE", runId);
            } catch (RuntimeException e) {
                // Defaut technique ou de parametrage : rien de la tentative ne reste, et la
                // transaction englobante reste utilisable pour la suite de l'arrete.
                rollbackTo(c, tentative);
                throw e;
            }
        });
    }

    private Execution succeed(Connection c, StandingOrder order, LocalDate on, int attempt,
                              UUID runId, UUID actorId) {
        Account debtor = Accounts.loadAll(c, Set.of(order.accountId())).get(order.accountId());
        if (debtor == null || !debtor.status().acceptsPosting()) {
            throw new Rejection("COMPTE_INOPERABLE");
        }
        ProductVersion product = ProductCatalog.resolveForAccount(c, order.legalEntityId(),
                                                                  debtor.id(), on);
        OperationsService.Charges charges = OperationsService.Charges.of(
            order.internal() ? DepositCatalog.transferFee(product, debtor.currency())
                             : DepositCatalog.paymentFee(product, debtor.currency()),
            product);
        Money amount = amountOf(c, order, debtor, on, charges);
        if (amount == null) {
            // Rien a balayer : ce n'est pas un echec, c'est une echeance sans objet.
            Execution execution = record(c, order, on, attempt, Outcome.SKIPPED,
                                         Money.zero(debtor.currency()),
                                         Money.zero(debtor.currency()),
                                         Money.zero(debtor.currency()),
                                         "RIEN_A_BALAYER", null, null, runId);
            advance(c, order, on);
            return execution;
        }
        // Le disponible se controle ici pour nommer le rejet — sans provision, et non un defaut
        // technique. Le ledger le controle encore au moment d'ecrire, sous verrou, et son refus
        // est ramene au point de sauvegarde ; mais tout compte ne porte pas ce controle, et le
        // client a droit au motif exact.
        Money total = amount.plus(charges.fee()).plus(charges.tax());
        if (total.isGreaterThan(Balances.available(c, debtor.id(), on))) {
            throw new Rejection("SANS_PROVISION");
        }
        if (order.internal()) {
            // Le beneficiaire se controle ici pour nommer le rejet : un compte clos par son
            // titulaire, ou dont le titulaire n'est plus operable, est la situation d'un client,
            // pas un defaut de la banque — elle n'a pas a arreter la journee.
            Account beneficiary = Accounts.loadAll(c, Set.of(order.beneficiaryAccountId()))
                .get(order.beneficiaryAccountId());
            if (beneficiary == null || !beneficiary.status().acceptsPosting()) {
                throw new Rejection("BENEFICIAIRE_INOPERABLE");
            }
        }
        IdempotencyKey key = key(order, runId);
        if (order.internal()) {
            OperationsService.Receipt receipt = operations.transfer(new OperationsService.Transfer(
                key, order.legalEntityId(), order.accountId(), order.beneficiaryAccountId(),
                amount, "BATCH", order.narrative(), actorId, runId));
            Execution execution = record(c, order, on, attempt, Outcome.EXECUTED, amount,
                                         receipt.fee(), receipt.tax(), null, receipt.entryId(),
                                         null, runId);
            advance(c, order, on);
            return execution;
        }
        PaymentService.Placed placed = payments.order(new PaymentService.Order(
            key, order.legalEntityId(), order.accountId(), amount, order.beneficiaryName(),
            order.beneficiaryBank(), order.beneficiaryAccount(), order.reference(), "BATCH",
            actorId, runId));
        Execution execution = record(c, order, on, attempt, Outcome.EXECUTED, amount,
                                     placed.order().fee(), placed.order().tax(), null,
                                     placed.order().entryId(), placed.order().id(), runId);
        advance(c, order, on);
        return execution;
    }

    private Execution fail(Connection c, StandingOrder order, LocalDate on, int attempt,
                           String reason, UUID runId) {
        Execution execution = record(c, order, on, attempt, Outcome.REJECTED,
                                     order.amount() == null
                                         ? Money.zero(currencyOf(c, order)) : order.amount(),
                                     Money.zero(currencyOf(c, order)),
                                     Money.zero(currencyOf(c, order)), reason, null, null, runId);
        if (attempt >= order.maxAttempts()) {
            // Les tentatives sont epuisees : l'echeance est abandonnee, la suivante reste due a
            // sa date. Reporter indefiniment ferait partir deux loyers le meme mois.
            advance(c, order, on);
        } else {
            retry(c, order, on, attempt);
        }
        return execution;
    }

    /**
     * Le montant de l'echeance : celui de l'ordre, ou ce qui depasse le plancher.
     *
     * <p>Le balayage se calcule sur le <b>disponible</b>, pas sur le solde : virer un blocage ou
     * un decouvert autorise viderait le compte de ce qui n'est pas au client. Il se calcule net
     * de ses frais, pour que le plancher promis reste sur le compte.
     */
    private static Money amountOf(Connection c, StandingOrder order, Account debtor, LocalDate on,
                                  OperationsService.Charges charges) {
        if (order.kind() == Kind.FIXED) {
            return order.amount();
        }
        Money available = Balances.available(c, debtor.id(), on);
        // Les frais sortent de ce qui est balaye, pas du plancher : le plancher est ce que le
        // client a demande a garder, et un balayage qui le creuserait de ses propres frais
        // trahirait l'ordre qu'il execute.
        Money excess = available.minus(order.floorAmount()).minus(charges.fee())
            .minus(charges.tax());
        return excess.isPositive() ? excess.roundToCurrency() : null;
    }

    /** L'echeance suivante, ramenee au jour ouvre suivant si elle tombe un jour ferie. */
    private void advance(Connection c, StandingOrder order, LocalDate on) {
        int next = order.occurrence() + 1;
        // L'echeance se calcule depuis la date de debut, jamais de proche en proche : un ordre
        // au 31 ramene au 28 en fevrier resterait au 28 ensuite, et changerait de jour sans que
        // personne ne l'ait decide.
        LocalDate raw = order.frequency().startOfPeriod(order.startDate(), next);
        LocalDate dueDate = businessDay(c, order.legalEntityId(), raw);
        boolean finished = (order.occurrences() != null && next >= order.occurrences())
                           || (order.endDate() != null && raw.isAfter(order.endDate()));
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE standing_order SET occurrence = ?, due_date = ?, next_attempt_date = ?,"
            + " attempts = 0, status = ? WHERE id = ?")) {
            ps.setInt(1, next);
            ps.setObject(2, dueDate);
            ps.setObject(3, dueDate);
            ps.setString(4, finished ? "COMPLETED" : "ACTIVE");
            ps.setObject(5, order.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Echeance suivante de l'ordre permanent", e);
        }
    }

    /** Une tentative de plus, le jour ouvre suivant : l'echeance, elle, ne bouge pas. */
    private void retry(Connection c, StandingOrder order, LocalDate on, int attempt) {
        LocalDate retryOn = businessDay(c, order.legalEntityId(), on.plusDays(1));
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE standing_order SET attempts = ?, next_attempt_date = ? WHERE id = ?")) {
            ps.setInt(1, attempt);
            ps.setObject(2, retryOn);
            ps.setObject(3, order.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Nouvelle tentative de l'ordre permanent", e);
        }
    }

    private Execution record(Connection c, StandingOrder order, LocalDate on, int attempt,
                             Outcome outcome, Money amount, Money fee, Money tax, String reason,
                             UUID entryId, UUID paymentOrderId, UUID runId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO standing_order_execution(id, standing_order_id, occurrence, due_date,"
            + " attempted_on, attempt, outcome, amount, fee, tax, reason, entry_id,"
            + " payment_order_id, batch_run_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, order.id());
            ps.setInt(3, order.occurrence());
            ps.setObject(4, order.dueDate());
            ps.setObject(5, on);
            ps.setInt(6, attempt);
            ps.setString(7, outcome.name());
            ps.setBigDecimal(8, amount.amount());
            ps.setBigDecimal(9, fee.amount());
            ps.setBigDecimal(10, tax.amount());
            ps.setString(11, reason);
            ps.setObject(12, entryId);
            ps.setObject(13, paymentOrderId);
            ps.setObject(14, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'execution", e);
        }
        return new Execution(id, order.id(), order.dueDate(), on, attempt, outcome, amount,
                             fee, tax, reason, entryId, paymentOrderId);
    }

    /**
     * Rend a l'attente les echeances executees par un arrete annule.
     *
     * <p>Les ecritures, elles, sont contre-passees par le moteur : ici on remet l'ordre dans
     * l'etat ou l'arrete l'a trouve — a son echeance, sans tentative consommee.
     */
    public static int cancelRun(Connection c, UUID runId, LocalDate on) {
        voidPlacedPayments(c, runId, on);
        List<Object[]> undone = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            // La plus ancienne echeance touchee par l'arrete, et la premiere tentative qu'il a
            // faite dessus : c'est l'etat dans lequel il a trouve l'ordre.
            "SELECT standing_order_id, MIN(occurrence) FROM standing_order_execution"
            + " WHERE batch_run_id = ? GROUP BY standing_order_id")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    undone.add(new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)});
                }
            }
            for (Object[] row : undone) {
                try (PreparedStatement update = c.prepareStatement(
                    // L'echeance rendue est celle que la premiere tentative de l'arrete portait ;
                    // ses tentatives anterieures, elles, restent consommees : c'est l'arrete qu'on
                    // annule, pas les journees d'avant. Un ordre acheve par l'arrete redevient
                    // actif, puisque l'echeance qui l'a acheve est a refaire — mais une
                    // revocation, elle, est un acte du client posterieur a l'arrete : defaire
                    // l'arrete ne la defait pas, et un ordre revoque le reste.
                    "UPDATE standing_order o SET occurrence = e.occurrence, due_date = e.due_date,"
                    + "   next_attempt_date = e.due_date, attempts = e.attempt - 1,"
                    + "   status = CASE WHEN o.status = 'CANCELLED' THEN 'CANCELLED'"
                    + "                 ELSE 'ACTIVE' END"
                    + "  FROM standing_order_execution e"
                    + " WHERE e.standing_order_id = o.id AND e.batch_run_id = ?"
                    + "   AND e.occurrence = ? AND o.id = ?"
                    + "   AND e.attempt = (SELECT MIN(x.attempt) FROM standing_order_execution x"
                    + "                     WHERE x.batch_run_id = e.batch_run_id"
                    + "                       AND x.standing_order_id = e.standing_order_id"
                    + "                       AND x.occurrence = e.occurrence)")) {
                    update.setObject(1, runId);
                    update.setInt(2, (Integer) row[1]);
                    update.setObject(3, row[0]);
                    update.executeUpdate();
                }
            }
            try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM standing_order_execution WHERE batch_run_id = ?")) {
                delete.setObject(1, runId);
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des ordres permanents du traitement", e);
        }
        return undone.size();
    }

    /**
     * Annule les ordres de paiement deposes par l'arrete.
     *
     * <p>Les ecritures sont contre-passees par le moteur ; ce qui resterait sans cela, c'est un
     * ordre de paiement en attente d'envoi dont le debit du client n'existe plus — et que le
     * correspondant paierait quand meme. On le solde donc ici, sans contre-passation propre :
     * elle ferait double emploi.
     *
     * <p>Si l'un d'eux a deja ete envoye ou regle, l'argent est parti : l'arrete ne s'annule
     * plus, et on le dit avant que rien ne soit defait.
     */
    /**
     * Verifie que les ordres de paiement deposes par un arrete sont encore a annuler.
     *
     * <p>Appelee par le moteur <b>avant la premiere contre-passation</b> : si l'un d'eux est
     * parti, le refus doit venir avant que rien ne soit defait, pas au milieu.
     */
    public static void requireCancellable(Connection c, UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT p.id, p.status FROM standing_order_execution e"
            + "  JOIN payment_order p ON p.id = e.payment_order_id"
            + " WHERE e.batch_run_id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString(2);
                    if (!"ORDERED".equals(status)) {
                        throw new IllegalStateException("L'ordre de paiement "
                            + rs.getObject(1, UUID.class) + " depose par ce traitement est depuis "
                            + "en etat " + status + " : la suite s'appuie sur lui, le traitement "
                            + "ne s'annule plus");
                    }
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Paiements deposes par le traitement", e);
        }
    }

    private static void voidPlacedPayments(Connection c, UUID runId, LocalDate on) {
        List<Object[]> placed = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            // La contre-passation est deja faite par le moteur, avant cette etape : on la
            // retrouve par le journal, et c'est elle qui porte l'annulation de l'ordre.
            "SELECT p.id, p.status, r.reversal_entry_id FROM standing_order_execution e"
            + "  JOIN payment_order p ON p.id = e.payment_order_id"
            + "  LEFT JOIN journal_reversal r ON r.reversed_entry_id = p.entry_id"
            + " WHERE e.batch_run_id = ? FOR UPDATE OF p")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    String status = rs.getString(2);
                    if (!"ORDERED".equals(status)) {
                        throw new IllegalStateException("L'ordre de paiement " + id + " depose par "
                            + "ce traitement est depuis en etat " + status + " : la suite "
                            + "s'appuie sur lui, le traitement ne s'annule plus");
                    }
                    UUID reversal = rs.getObject(3, UUID.class);
                    if (reversal == null) {
                        throw new IllegalStateException("L'ecriture de l'ordre de paiement " + id
                            + " n'a pas ete contre-passee : le traitement ne s'annule pas a "
                            + "moitie");
                    }
                    placed.add(new Object[] {id, reversal});
                }
            }
            for (Object[] row : placed) {
                try (PreparedStatement update = c.prepareStatement(
                    "UPDATE payment_order SET status = 'CANCELLED', cancelled_on = ?,"
                    + " cancel_entry_id = ?, cancel_reason = ? WHERE id = ?")) {
                    update.setObject(1, on);
                    update.setObject(2, row[1]);
                    update.setString(3, "Arrete annule : l'ordre permanent redevient du");
                    update.setObject(4, row[0]);
                    update.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des paiements deposes par le traitement", e);
        }
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT o.id, o.legal_entity_id, o.account_id, o.reference, o.kind, o.amount,"
        + " o.floor_amount, o.beneficiary_account_id, o.beneficiary_name, o.beneficiary_bank,"
        + " o.beneficiary_account, o.frequency, o.start_date, o.end_date, o.occurrence,"
        + " o.occurrences, o.due_date, o.next_attempt_date, o.attempts, o.max_attempts, o.status,"
        + " o.narrative,"
        + " o.cancelled_on, o.cancel_reason, o.created_by, o.approved_by, cur.code, cur.scale,"
        + " cur.rounding_mode"
        + "  FROM standing_order o JOIN account a ON a.id = o.account_id"
        + "  JOIN currency cur ON cur.code = a.currency";

    public static StandingOrder require(Connection c, UUID id) {
        return find(c, id).orElseThrow(
            () -> new IllegalArgumentException("Ordre permanent inconnu : " + id));
    }

    public static Optional<StandingOrder> find(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE o.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'ordre permanent " + id, e);
        }
    }

    /** Les ordres d'une entite, filtres par statut si l'appelant en nomme un. */
    public static List<StandingOrder> orders(Connection c, UUID legalEntityId, String status) {
        List<StandingOrder> orders = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE o.legal_entity_id = ? AND (?::text IS NULL OR o.status = ?)"
            + " ORDER BY o.reference")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des ordres permanents", e);
        }
        return orders;
    }

    /** Ce que chaque echeance a donne, de la plus recente a la plus ancienne. */
    public static List<Execution> executions(Connection c, UUID standingOrderId) {
        StandingOrder order = require(c, standingOrderId);
        List<Execution> executions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, standing_order_id, due_date, attempted_on, attempt, outcome, amount, fee,"
            + " tax, reason, entry_id, payment_order_id FROM standing_order_execution"
            + " WHERE standing_order_id = ? ORDER BY due_date DESC, attempt DESC")) {
            ps.setObject(1, standingOrderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    io.corebanking.kernel.money.CurrencyRef currency = order.amount() != null
                        ? order.amount().currency() : order.floorAmount().currency();
                    executions.add(new Execution(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                        rs.getInt(5), Outcome.valueOf(rs.getString(6)),
                        Money.of(rs.getBigDecimal(7), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(8), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(9), currency).roundToCurrency(),
                        rs.getString(10), rs.getObject(11, UUID.class),
                        rs.getObject(12, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des executions", e);
        }
        return executions;
    }

    private static StandingOrder read(ResultSet rs) throws SQLException {
        io.corebanking.kernel.money.CurrencyRef currency =
            new io.corebanking.kernel.money.CurrencyRef(
                rs.getString(27), rs.getInt(28),
                java.math.RoundingMode.valueOf(rs.getString(29)));
        java.math.BigDecimal amount = rs.getBigDecimal(6);
        java.math.BigDecimal floor = rs.getBigDecimal(7);
        return new StandingOrder(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            rs.getString(4), Kind.valueOf(rs.getString(5)),
            amount == null ? null : Money.of(amount, currency).roundToCurrency(),
            floor == null ? null : Money.of(floor, currency).roundToCurrency(),
            rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10), rs.getString(11),
            Periodicity.valueOf(rs.getString(12)), rs.getObject(13, LocalDate.class),
            rs.getObject(14, LocalDate.class), rs.getInt(15), rs.getObject(16, Integer.class),
            rs.getObject(17, LocalDate.class), rs.getObject(18, LocalDate.class), rs.getInt(19),
            rs.getInt(20), rs.getString(21), rs.getString(22),
            rs.getObject(23, LocalDate.class), rs.getString(24), rs.getObject(25, UUID.class),
            rs.getObject(26, UUID.class));
    }

    private StandingOrder lock(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM standing_order WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Ordre permanent inconnu : " + id);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou de l'ordre permanent " + id, e);
        }
        return require(c, id);
    }

    private static io.corebanking.kernel.money.CurrencyRef currencyOf(Connection c,
                                                                      StandingOrder order) {
        return order.amount() != null ? order.amount().currency() : order.floorAmount().currency();
    }

    /** Une echeance qui tombe un jour ferie se traite le jour ouvre suivant, jamais avant. */
    private LocalDate businessDay(Connection c, UUID legalEntityId, LocalDate date) {
        return Calendars.load(database, legalEntityId).calendar().nextBusinessDayOrSame(date);
    }

    /**
     * La cle d'idempotence porte l'echeance et la tentative : deux tentatives d'une meme echeance
     * sont deux operations, et le rejeu d'un arrete en est une seule.
     */
    /**
     * La cle d'idempotence d'une echeance.
     *
     * <p>Elle porte le traitement : rejouer un arrete annule doit ecrire de nouveau. Sous une cle
     * qui ignorerait le traitement, le rejeu retrouverait l'ecriture d'origine — contre-passee —
     * et croirait avoir vire ; l'ordre avancerait d'une echeance sans que l'argent bouge.
     * Dans le meme traitement, en revanche, la cle ne change pas : une reprise ne vire pas deux
     * fois.
     */
    private static IdempotencyKey key(StandingOrder order, UUID runId) {
        return runId == null
            ? IdempotencyKey.of("SO|" + order.id() + "|" + order.dueDate() + "|"
                                + order.attempts())
            : IdempotencyKey.forBatch(runId.toString(), STEP, order.id(), order.dueDate(),
                                      order.attempts());
    }

    private static Savepoint savepoint(Connection c) {
        try {
            return c.setSavepoint();
        } catch (SQLException e) {
            throw new LedgerStoreException("Point de sauvegarde", e);
        }
    }

    private static void rollbackTo(Connection c, Savepoint savepoint) {
        try {
            c.rollback(savepoint);
        } catch (SQLException e) {
            throw new LedgerStoreException("Retour au point de sauvegarde", e);
        }
    }
}
