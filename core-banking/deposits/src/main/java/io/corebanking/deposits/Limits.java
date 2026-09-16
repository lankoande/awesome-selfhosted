package io.corebanking.deposits;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductVersion;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Plafonds d'operations : ce qu'un compte peut debiter par operation, par jour, par mois.
 *
 * <p>Le plafond vient du produit ; un compte peut porter le sien, negocie, a deux, par nature et
 * periode de validite — il remplace alors celui du produit, dans un sens comme dans l'autre.
 * L'usage se lit dans le journal : la somme des debits du client sur les operations soumises au
 * plafond — retraits, virements, paiements sortants —, frais compris, hors ecritures
 * contre-passees. Un compteur tenu a part se desynchroniserait a la premiere annulation ; le
 * journal, lui, est exact par construction.
 */
public final class Limits {

    private Limits() {}

    public enum Kind { TRANSACTION, DAILY, MONTHLY }

    /** Les operations qui consomment un plafond : les debits a l'initiative du client. */
    static final Set<String> LIMITED_TYPES = Set.of(OperationSchemas.CASH_WITHDRAWAL,
                                                    OperationSchemas.TRANSFER,
                                                    OperationSchemas.PAYMENT_ORDER);

    public record AccountLimit(UUID id, UUID accountId, Kind kind, Money amount,
                               LocalDate validFrom, LocalDate validTo, UUID createdBy,
                               UUID approvedBy) {}

    public record Draft(UUID legalEntityId, UUID accountId, Kind kind, Money amount,
                        LocalDate validFrom, LocalDate validTo, UUID createdBy, UUID approvedBy) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(validFrom, "validFrom");
        }
    }

    // ------------------------------------------------------------------ parametrage

    /** Pose un plafond sur un compte, a deux ; un plafond nul interdit l'operation. */
    public static UUID set(Connection c, Draft draft) {
        if (draft.createdBy() == null || draft.approvedBy() == null
            || draft.approvedBy().equals(draft.createdBy())) {
            throw new IllegalArgumentException(
                "Un plafond se pose a deux : le demandeur ne peut pas etre le valideur");
        }
        if (draft.amount().isNegative()) {
            throw new IllegalArgumentException("Un plafond n'est pas negatif : " + draft.amount());
        }
        if (draft.validTo() != null && draft.validTo().isBefore(draft.validFrom())) {
            throw new IllegalArgumentException("La fin de validite precede son debut");
        }
        Account account = Accounts.loadAll(c, Set.of(draft.accountId())).get(draft.accountId());
        if (account == null || !account.legalEntityId().equals(draft.legalEntityId())) {
            throw new IllegalArgumentException("Compte inconnu : " + draft.accountId());
        }
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " n'est pas un compte client : un plafond borne "
                + "ce qu'un client debite");
        }
        if (!account.currency().code().equals(draft.amount().currency().code())) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " est en " + account.currency().code()
                + ", le plafond en " + draft.amount().currency().code());
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_limit(id, legal_entity_id, account_id, kind, amount, valid_from,"
            + " valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setObject(3, draft.accountId());
            ps.setString(4, draft.kind().name());
            ps.setBigDecimal(5, draft.amount().amount());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.validTo());
            ps.setObject(8, draft.createdBy());
            ps.setObject(9, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                throw new IllegalStateException(
                    "Un plafond " + draft.kind() + " est deja en vigueur sur ce compte sur la "
                    + "periode : le borner avant d'en poser un autre", e);
            }
            throw new LedgerStoreException("Pose du plafond", e);
        }
        return id;
    }

    private static final String SELECT =
        "SELECT l.id, l.account_id, l.kind, l.amount, l.valid_from, l.valid_to, l.created_by,"
        + " l.approved_by, cur.code, cur.scale, cur.rounding_mode"
        + " FROM account_limit l JOIN account a ON a.id = l.account_id"
        + " JOIN currency cur ON cur.code = a.currency";

    public static List<AccountLimit> ofAccount(Connection c, UUID accountId) {
        List<AccountLimit> limits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE l.account_id = ? ORDER BY l.kind, l.valid_from")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    limits.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des plafonds du compte", e);
        }
        return limits;
    }

    /** Le plafond propre au compte en vigueur a une date, s'il en a un. */
    public static Optional<AccountLimit> ofAccountOn(Connection c, UUID accountId, Kind kind,
                                                     LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE l.account_id = ? AND l.kind = ? AND l.valid_from <= ?"
            + " AND (l.valid_to IS NULL OR l.valid_to >= ?)")) {
            ps.setObject(1, accountId);
            ps.setString(2, kind.name());
            ps.setObject(3, on);
            ps.setObject(4, on);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du plafond du compte", e);
        }
    }

    private static AccountLimit read(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(9), rs.getInt(10),
                                               java.math.RoundingMode.valueOf(rs.getString(11)));
        return new AccountLimit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                Kind.valueOf(rs.getString(3)),
                                Money.of(rs.getBigDecimal(4), currency),
                                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class),
                                rs.getObject(7, UUID.class), rs.getObject(8, UUID.class));
    }

    // ------------------------------------------------------------------ controle

    /** Le plafond en vigueur : celui du compte a la date, sinon celui du produit, sinon aucun. */
    public static Optional<Money> effective(Connection c, Account account, ProductVersion product,
                                            Kind kind, LocalDate on) {
        Optional<AccountLimit> own = ofAccountOn(c, account.id(), kind, on);
        if (own.isPresent()) {
            return own.map(AccountLimit::amount);
        }
        String parameter = switch (kind) {
            case TRANSACTION -> DepositCatalog.P_TRANSACTION_MAX;
            case DAILY -> DepositCatalog.P_DAILY_DEBIT_MAX;
            case MONTHLY -> DepositCatalog.P_MONTHLY_DEBIT_MAX;
        };
        return DepositCatalog.limit(product, parameter, account.currency());
    }

    /**
     * Refuse un debit qui depasserait un plafond en vigueur. L'usage du jour et du mois est lu
     * dans le journal, en date comptable, frais compris, hors ecritures contre-passees.
     */
    public static void check(Connection c, Account account, ProductVersion product, Money amount,
                             LocalDate on) {
        Optional<Money> daily = effective(c, account, product, Kind.DAILY, on);
        Optional<Money> monthly = effective(c, account, product, Kind.MONTHLY, on);
        if (daily.isPresent() || monthly.isPresent()) {
            // Deux debits concurrents liraient chacun un usage sans l'autre et passeraient tous
            // deux : sous un plafond cumule, les debits d'un compte se suivent, sur le verrou
            // du compte, tenu jusqu'a la validation de l'ecriture.
            lock(c, account.id());
        }
        effective(c, account, product, Kind.TRANSACTION, on).ifPresent(limit -> {
            if (amount.isGreaterThan(limit)) {
                throw new LimitExceededException(account, Kind.TRANSACTION, limit,
                                                 Money.zero(amount.currency()), amount);
            }
        });
        daily.ifPresent(limit -> {
            Money used = debits(c, account.id(), on, on, account.currency());
            if (used.plus(amount).isGreaterThan(limit)) {
                throw new LimitExceededException(account, Kind.DAILY, limit, used, amount);
            }
        });
        monthly.ifPresent(limit -> {
            Money used = debits(c, account.id(), on.withDayOfMonth(1),
                                on.withDayOfMonth(on.lengthOfMonth()), account.currency());
            if (used.plus(amount).isGreaterThan(limit)) {
                throw new LimitExceededException(account, Kind.MONTHLY, limit, used, amount);
            }
        });
    }

    private static void lock(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM account WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, accountId);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrouillage du compte sous plafond", e);
        }
    }

    /** Les debits du client soumis au plafond sur une plage de dates comptables. */
    public static Money debits(Connection c, UUID accountId, LocalDate from, LocalDate to,
                               CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(l.amount), 0)"
            + " FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id"
            + " AND e.booking_date = l.booking_date"
            + " WHERE l.account_id = ? AND l.direction = 'DEBIT'"
            + " AND l.booking_date >= ? AND l.booking_date <= ?"
            + " AND e.transaction_type = ANY (?) AND e.reversal_of IS NULL"
            + " AND NOT EXISTS (SELECT 1 FROM journal_reversal r WHERE r.reversed_entry_id = e.id)")) {
            ps.setObject(1, accountId);
            ps.setObject(2, from);
            ps.setObject(3, to);
            ps.setArray(4, c.createArrayOf("text", LIMITED_TYPES.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return Money.of(rs.getBigDecimal(1), currency);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des debits soumis au plafond", e);
        }
    }

    /** Un plafond en vigueur refuse le debit : un conflit d'etat du compte, pas une requete fausse. */
    public static class LimitExceededException extends RuntimeException {
        private final Kind kind;

        public LimitExceededException(Account account, Kind kind, Money limit, Money used,
                                      Money requested) {
            super("Compte " + account.code() + " : plafond " + label(kind) + " de "
                  + limit.roundToCurrency() + " atteint ("
                  + (used.isZero() ? "" : used.roundToCurrency() + " deja debites, ")
                  + requested.roundToCurrency() + " demandes)");
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        private static String label(Kind kind) {
            return switch (kind) {
                case TRANSACTION -> "par operation";
                case DAILY -> "journalier";
                case MONTHLY -> "mensuel";
            };
        }
    }
}
