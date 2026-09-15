package io.corebanking.deposits;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Blocages de montant : une part du solde que le client ne peut plus engager.
 *
 * <p>Provision d'un cheque certifie, retenue de garantie, autorisation de paiement en attente,
 * opposition sur une somme : le blocage reduit le disponible sans mouvementer le compte, et le
 * ledger — pas ce service — refuse tout debit qui l'entamerait, prelevement automatique compris.
 * Un blocage expire par <b>date comptable</b>, a l'arrete de sa date de fin ; l'annulation de
 * l'arrete le repose.
 */
public final class Holds {

    private Holds() {}

    public record Placement(UUID accountId, Money amount, String type, String reference,
                            LocalDate placedOn, LocalDate expiresOn, UUID actorId) {
        public Placement {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(placedOn, "placedOn");
            Objects.requireNonNull(actorId, "actorId");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("Un blocage porte un montant positif : " + amount);
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Nature du blocage obligatoire");
            }
            if (expiresOn != null && expiresOn.isBefore(placedOn)) {
                throw new IllegalArgumentException("Un blocage n'expire pas avant d'etre pose");
            }
        }
    }

    public record Hold(UUID id, UUID accountId, Money amount, String type, String reference,
                       LocalDate placedOn, LocalDate expiresOn, LocalDate releasedOn) {}

    public static UUID place(Connection c, Placement placement) {
        Account account = Accounts.loadAll(c, Set.of(placement.accountId()))
            .get(placement.accountId());
        if (account == null) {
            throw new IllegalArgumentException("Compte inconnu : " + placement.accountId());
        }
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException(
                "Un blocage de montant porte sur un compte client, pas sur " + account.code());
        }
        if (!account.currency().equals(placement.amount().currency())) {
            throw new IllegalArgumentException(
                "Blocage en " + placement.amount().currency().code() + " sur un compte en "
                + account.currency().code());
        }
        if (!account.status().acceptsPosting()) {
            throw new IllegalStateException("Le compte " + account.code() + " est "
                                            + account.status() + " : rien a bloquer");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_hold(id, account_id, amount, currency, hold_type, reference,"
            + " expires_on, placed_on, placed_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, placement.accountId());
            ps.setBigDecimal(3, placement.amount().amount());
            ps.setString(4, placement.amount().currency().code());
            ps.setString(5, placement.type());
            ps.setString(6, placement.reference());
            ps.setObject(7, placement.expiresOn());
            ps.setObject(8, placement.placedOn());
            ps.setObject(9, placement.actorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Pose du blocage sur " + account.code(), e);
        }
        return id;
    }

    public static void release(Connection c, UUID holdId, LocalDate on, UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account_hold SET released_at = now(), released_on = ?, released_by = ?"
            + " WHERE id = ? AND released_at IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, actorId);
            ps.setObject(3, holdId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Blocage " + holdId + " inconnu ou deja leve : un blocage ne se leve qu'une fois");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Levee du blocage " + holdId, e);
        }
    }

    /** Blocages en vigueur sur un compte. */
    public static List<Hold> activeOn(Connection c, UUID accountId) {
        List<Hold> holds = new ArrayList<>();
        CurrencyRef currency = io.corebanking.ledger.store.Balances.currencyOf(c, accountId);
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, amount, hold_type, reference, placed_on, expires_on FROM account_hold"
            + " WHERE account_id = ? AND released_at IS NULL ORDER BY created_at")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    holds.add(new Hold(rs.getObject(1, UUID.class), accountId,
                                       Money.of(rs.getBigDecimal(2), currency), rs.getString(3),
                                       rs.getString(4), rs.getObject(5, LocalDate.class),
                                       rs.getObject(6, LocalDate.class), null));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Blocages du compte " + accountId, e);
        }
        return holds;
    }

    /**
     * Leve les blocages arrives a leur date de fin : ceux dont la date d'expiration est la journee
     * arretee ou lui est anterieure. Le disponible ne les compte deja plus le lendemain.
     */
    public static int expire(Connection c, UUID legalEntityId, LocalDate businessDate,
                             UUID batchRunId, UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account_hold h SET released_at = now(), released_on = ?, released_by = ?,"
            + " released_run_id = ?"
            + " FROM account a WHERE a.id = h.account_id AND a.legal_entity_id = ?"
            + "   AND h.released_at IS NULL AND h.expires_on IS NOT NULL AND h.expires_on <= ?")) {
            ps.setObject(1, businessDate);
            ps.setObject(2, actorId);
            ps.setObject(3, batchRunId);
            ps.setObject(4, legalEntityId);
            ps.setObject(5, businessDate);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Expiration des blocages", e);
        }
    }

    /** Repose les blocages leves par un traitement annule. */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE account_hold SET released_at = NULL, released_on = NULL, released_by = NULL,"
            + " released_run_id = NULL WHERE released_run_id = ?")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Retablissement des blocages du traitement", e);
        }
    }
}
