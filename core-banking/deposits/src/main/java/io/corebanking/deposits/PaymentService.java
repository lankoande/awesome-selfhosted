package io.corebanking.deposits;

import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.expr.EvaluationContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Paiements sortants : un ordre de virement vers un beneficiaire hors de l'entite.
 *
 * <p>Le client est debite <b>a l'ordre</b> — montant, frais et taxe — et le montant va au compte
 * de reglement sortant du produit : les fonds ne sont pas encore chez le correspondant, et le
 * bilan le montre. L'ordre est ensuite <b>envoye</b>, puis <b>regle</b> sur le nostro quand le
 * correspondant confirme, ou <b>retourne</b> — les fonds reviennent au client, les frais restent
 * acquis — ; avant envoi, il s'<b>annule</b> par contre-passation de l'ecriture d'ordre. Chaque
 * etat porte sa date et son ecriture, et ne se defait que par l'etat suivant. Les plafonds du
 * produit et du compte s'appliquent a l'ordre comme a un retrait.
 */
public final class PaymentService {

    private final Database database;
    private final PostingService postingService;

    public PaymentService(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    public record Order(IdempotencyKey key, UUID legalEntityId, UUID accountId, Money amount,
                        String beneficiaryName, String beneficiaryBank, String beneficiaryAccount,
                        String reference, String channel, UUID actorId) {
        public Order {
            OperationsService.requireCommand(key, legalEntityId, accountId, amount, actorId);
            for (String field : List.of(String.valueOf(beneficiaryName),
                                        String.valueOf(beneficiaryBank),
                                        String.valueOf(beneficiaryAccount))) {
                if (field.isBlank() || "null".equals(field)) {
                    throw new IllegalArgumentException(
                        "Un paiement sortant designe son beneficiaire : nom, banque, compte");
                }
            }
        }
    }

    public record PaymentOrder(UUID id, UUID legalEntityId, UUID accountId, Money amount,
                               Money fee, Money tax, String beneficiaryName,
                               String beneficiaryBank, String beneficiaryAccount,
                               String reference, String channel, String status,
                               LocalDate orderedOn, UUID entryId, UUID clearingAccountId,
                               LocalDate sentOn, LocalDate settledOn, UUID settlementAccountId,
                               UUID settlementEntryId, LocalDate returnedOn, UUID returnEntryId,
                               String returnReason, LocalDate cancelledOn, UUID cancelEntryId,
                               String cancelReason, UUID createdBy) {}

    /** L'ordre, et s'il s'agit d'un rejeu : rien n'a alors ete comptabilise de nouveau. */
    public record Placed(PaymentOrder order, boolean replayed) {}

    // ------------------------------------------------------------------ ordre

    public Placed order(Order command) {
        return database.inTransaction(c -> {
            Optional<PaymentOrder> existing = byKey(c, command.legalEntityId(), command.key());
            if (existing.isPresent()) {
                return new Placed(existing.get(), true);
            }
            LocalDate bookingDate = OperationsService.businessDate(c, command.legalEntityId());
            Account account = OperationsService.requireOperableAccount(
                c, command.legalEntityId(), command.accountId(), command.amount(), bookingDate);
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, command.legalEntityId(), account.id(), bookingDate);
            UUID clearingId = DepositCatalog.paymentClearing(product).orElseThrow(() ->
                new NotAllowedException("Le produit " + product.code() + " n'admet pas de "
                    + "paiement sortant : aucun compte de reglement sortant n'y est declare"));
            Account clearing = OperationsService.requireInternal(c, command.legalEntityId(),
                                                                 clearingId, account.currency());
            Limits.check(c, account, product, command.amount(), bookingDate);
            OperationsService.Charges charges = OperationsService.Charges.of(
                DepositCatalog.paymentFee(product, account.currency()), product);
            ValueDatePolicy policy = Calendars.load(database, command.legalEntityId());
            LocalDate valueDate = policy.valueDateFor(OperationSchemas.PAYMENT_ORDER,
                                                      command.channel(), Direction.DEBIT,
                                                      bookingDate);
            List<PostingLine> lines = OperationsService.lines(
                OperationSchemas.paymentOrder(account.currency()),
                EvaluationContext.builder().put("amount", command.amount())
                    .put("fee", charges.fee()).put("tax", charges.tax()).build(),
                account, charges.roles(Map.of(OperationSchemas.ROLE_CLEARING, clearing.id()),
                                       product),
                bookingDate);
            lines = OperationsService.withValueDate(lines, account.id(), valueDate);
            PostingResult result = postingService.post(PostingCommand.online(
                command.key(), command.legalEntityId(), bookingDate,
                OperationSchemas.PAYMENT_ORDER, command.actorId(), lines)
                .withBranch(account.branchId()));
            OperationsService.wakeIfDormant(c, account, bookingDate, command.actorId(), result);

            UUID id = Ids.newId();
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payment_order(id, legal_entity_id, account_id, idempotency_key,"
                + " amount, currency, fee, tax, beneficiary_name, beneficiary_bank,"
                + " beneficiary_account, reference, channel, ordered_on, entry_id,"
                + " clearing_account_id, created_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, command.legalEntityId());
                ps.setObject(3, account.id());
                ps.setString(4, command.key().value());
                ps.setBigDecimal(5, command.amount().amount());
                ps.setString(6, account.currency().code());
                ps.setBigDecimal(7, charges.fee().amount());
                ps.setBigDecimal(8, charges.tax().amount());
                ps.setString(9, command.beneficiaryName().trim());
                ps.setString(10, command.beneficiaryBank().trim());
                ps.setString(11, command.beneficiaryAccount().trim());
                ps.setString(12, command.reference());
                ps.setString(13, command.channel());
                ps.setObject(14, bookingDate);
                ps.setObject(15, result.entryId());
                ps.setObject(16, clearing.id());
                ps.setObject(17, command.actorId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Enregistrement de l'ordre de paiement", e);
            }
            return new Placed(require(c, id), false);
        });
    }

    // ------------------------------------------------------------------ cycle de vie

    /** L'ordre part vers le systeme de paiement : plus d'annulation, seulement un retour. */
    public PaymentOrder send(UUID orderId, UUID actorId) {
        return database.inTransaction(c -> {
            PaymentOrder order = lock(c, orderId);
            requireStatus(order, "ORDERED");
            LocalDate on = OperationsService.businessDate(c, order.legalEntityId());
            update(c, "UPDATE payment_order SET status = 'SENT', sent_on = ? WHERE id = ?", on,
                   orderId);
            return require(c, orderId);
        });
    }

    /** Le correspondant a paye : le compte de reglement sortant se solde sur le nostro. */
    public PaymentOrder settle(UUID orderId, UUID nostroAccountId, UUID actorId) {
        return database.inTransaction(c -> {
            PaymentOrder order = lock(c, orderId);
            requireStatus(order, "SENT");
            Account nostro = Accounts.loadAll(c, Set.of(nostroAccountId)).get(nostroAccountId);
            if (nostro == null || !nostro.legalEntityId().equals(order.legalEntityId())
                || nostro.kind() != AccountKind.NOSTRO
                || !nostro.currency().code().equals(order.amount().currency().code())) {
                throw new IllegalArgumentException(
                    "Le reglement se fait sur un compte nostro de l'entite, en "
                    + order.amount().currency().code() + " : " + nostroAccountId + " n'en est pas un");
            }
            LocalDate on = OperationsService.businessDate(c, order.legalEntityId());
            String narrative = "Reglement du paiement " + order.id() + " a "
                + order.beneficiaryName();
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of(OperationSchemas.PAYMENT_SETTLEMENT + "-" + order.id()),
                order.legalEntityId(), on, OperationSchemas.PAYMENT_SETTLEMENT, actorId,
                List.of(PostingLine.debit(order.clearingAccountId(), order.amount(), on, narrative),
                        PostingLine.credit(nostro.id(), order.amount(), on, narrative)))
                .withBranch(Branches.headOffice(c, order.legalEntityId())));
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_order SET status = 'SETTLED', settled_on = ?,"
                + " settlement_account_id = ?, settlement_entry_id = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, nostro.id());
                ps.setObject(3, result.entryId());
                ps.setObject(4, orderId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Reglement de l'ordre de paiement", e);
            }
            return require(c, orderId);
        });
    }

    /**
     * Le paiement revient — beneficiaire inconnu, compte clos chez le correspondant : le montant
     * est rendu au client, du compte de reglement s'il n'etait pas regle, du nostro sinon. Les
     * frais restent acquis : le service a ete rendu.
     */
    public PaymentOrder returnOrder(UUID orderId, String reason, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Le motif du retour est obligatoire");
        }
        return database.inTransaction(c -> {
            PaymentOrder order = lock(c, orderId);
            if (!"SENT".equals(order.status()) && !"SETTLED".equals(order.status())) {
                throw new PaymentStateException(order, "un retour suppose un ordre envoye ou regle");
            }
            LocalDate on = OperationsService.businessDate(c, order.legalEntityId());
            UUID from = "SETTLED".equals(order.status()) ? order.settlementAccountId()
                                                          : order.clearingAccountId();
            Account customer = Accounts.loadAll(c, Set.of(order.accountId())).get(order.accountId());
            String narrative = "Retour du paiement " + order.id() + " : " + reason.trim();
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of(OperationSchemas.PAYMENT_RETURN + "-" + order.id()),
                order.legalEntityId(), on, OperationSchemas.PAYMENT_RETURN, actorId,
                List.of(PostingLine.debit(from, order.amount(), on, narrative),
                        PostingLine.credit(order.accountId(), order.amount(), on, narrative)))
                .withBranch(customer.branchId()));
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_order SET status = 'RETURNED', returned_on = ?,"
                + " return_entry_id = ?, return_reason = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, result.entryId());
                ps.setString(3, reason.trim());
                ps.setObject(4, orderId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Retour de l'ordre de paiement", e);
            }
            return require(c, orderId);
        });
    }

    /** Avant envoi, l'ordre s'annule : l'ecriture d'ordre est contre-passee, frais compris. */
    public PaymentOrder cancel(UUID orderId, String reason, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Le motif de l'annulation est obligatoire");
        }
        return database.inTransaction(c -> {
            PaymentOrder order = lock(c, orderId);
            requireStatus(order, "ORDERED");
            LocalDate on = OperationsService.businessDate(c, order.legalEntityId());
            PostingResult result = postingService.reverse(
                order.entryId(), order.orderedOn(), on,
                IdempotencyKey.of("PAYMENT_CANCEL-" + order.id()),
                "Annulation du paiement " + order.id() + " : " + reason.trim());
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE payment_order SET status = 'CANCELLED', cancelled_on = ?,"
                + " cancel_entry_id = ?, cancel_reason = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, result.entryId());
                ps.setString(3, reason.trim());
                ps.setObject(4, orderId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Annulation de l'ordre de paiement", e);
            }
            return require(c, orderId);
        });
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT p.id, p.legal_entity_id, p.account_id, p.amount, p.fee, p.tax, p.beneficiary_name,"
        + " p.beneficiary_bank, p.beneficiary_account, p.reference, p.channel, p.status,"
        + " p.ordered_on, p.entry_id, p.clearing_account_id, p.sent_on, p.settled_on,"
        + " p.settlement_account_id, p.settlement_entry_id, p.returned_on, p.return_entry_id,"
        + " p.return_reason, p.cancelled_on, p.cancel_entry_id, p.cancel_reason, p.created_by,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM payment_order p JOIN currency cur ON cur.code = p.currency";

    public static Optional<PaymentOrder> find(Connection c, UUID orderId) {
        return one(c, SELECT + " WHERE p.id = ?", ps -> ps.setObject(1, orderId));
    }

    public static PaymentOrder require(Connection c, UUID orderId) {
        return find(c, orderId).orElseThrow(() -> new UnknownPaymentOrderException(orderId));
    }

    private static PaymentOrder lock(Connection c, UUID orderId) {
        return one(c, SELECT + " WHERE p.id = ? FOR UPDATE OF p", ps -> ps.setObject(1, orderId))
            .orElseThrow(() -> new UnknownPaymentOrderException(orderId));
    }

    private static Optional<PaymentOrder> byKey(Connection c, UUID legalEntityId,
                                                IdempotencyKey key) {
        return one(c, SELECT + " WHERE p.legal_entity_id = ? AND p.idempotency_key = ?", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setString(2, key.value());
        });
    }

    /** Les ordres de l'entite, du plus recent au plus ancien, par statut s'il est donne. */
    public static List<PaymentOrder> page(Connection c, UUID legalEntityId, String status,
                                          int offset, int limit) {
        List<PaymentOrder> orders = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE p.legal_entity_id = ? AND (?::text IS NULL OR p.status = ?)"
            + " ORDER BY p.ordered_on DESC, p.created_at DESC, p.id OFFSET ? LIMIT ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            ps.setInt(4, offset);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des ordres de paiement", e);
        }
        return orders;
    }

    public static long count(Connection c, UUID legalEntityId, String status) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM payment_order p WHERE p.legal_entity_id = ?"
            + " AND (?::text IS NULL OR p.status = ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte des ordres de paiement", e);
        }
    }

    // ------------------------------------------------------------------ interne

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Optional<PaymentOrder> one(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'ordre de paiement", e);
        }
    }

    private static PaymentOrder read(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(27), rs.getInt(28),
                                               RoundingMode.valueOf(rs.getString(29)));
        return new PaymentOrder(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), Money.of(rs.getBigDecimal(4), currency),
            Money.of(rs.getBigDecimal(5), currency), Money.of(rs.getBigDecimal(6), currency),
            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getString(12), rs.getObject(13, LocalDate.class), rs.getObject(14, UUID.class),
            rs.getObject(15, UUID.class), rs.getObject(16, LocalDate.class),
            rs.getObject(17, LocalDate.class), rs.getObject(18, UUID.class),
            rs.getObject(19, UUID.class), rs.getObject(20, LocalDate.class),
            rs.getObject(21, UUID.class), rs.getString(22), rs.getObject(23, LocalDate.class),
            rs.getObject(24, UUID.class), rs.getString(25), rs.getObject(26, UUID.class));
    }

    private static void update(Connection c, String sql, LocalDate on, UUID orderId) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, on);
            ps.setObject(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de l'ordre de paiement", e);
        }
    }

    private static void requireStatus(PaymentOrder order, String expected) {
        if (!expected.equals(order.status())) {
            throw new PaymentStateException(order, "attendu " + expected);
        }
    }

    public static class UnknownPaymentOrderException extends RuntimeException {
        public UnknownPaymentOrderException(UUID orderId) {
            super("Ordre de paiement inconnu : " + orderId);
        }
    }

    /** L'ordre n'est pas dans l'etat que la transition suppose : un conflit, pas une requete fausse. */
    public static class PaymentStateException extends IllegalStateException {
        public PaymentStateException(PaymentOrder order, String detail) {
            super("Ordre de paiement " + order.id() + " en etat " + order.status() + " : " + detail);
        }
    }

    /** Le produit du compte n'admet pas l'operation. */
    public static class NotAllowedException extends IllegalStateException {
        public NotAllowedException(String detail) {
            super(detail);
        }
    }
}
