package io.corebanking.ledger.store;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Referentiel des comptes et amorcage de leurs sous-soldes. */
public final class Accounts {

    private Accounts() {}

    /**
     * Cree un compte et <b>toutes</b> ses lignes de solde, une par stripe.
     *
     * <p>Les lignes sont creees ici plutot qu'a la premiere imputation : la comptabilisation n'a
     * alors qu'a faire un {@code UPDATE}, sans {@code INSERT ... ON CONFLICT} qui, sur un compte
     * chaud, redeviendrait un point de contention.
     */
    public static void create(Connection c, Account account) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account(id, legal_entity_id, code, account_kind, normal_balance, currency,"
            + " gl_account_id, contract_id, postable, control_available, stripe_count, status,"
            + " opened_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, account.id());
            ps.setObject(2, account.legalEntityId());
            ps.setString(3, account.code());
            ps.setString(4, account.kind().name());
            ps.setString(5, account.normalBalance().name());
            ps.setString(6, account.currency().code());
            ps.setObject(7, null);
            ps.setObject(8, null);
            ps.setBoolean(9, account.postable());
            ps.setBoolean(10, account.controlAvailable());
            ps.setInt(11, account.stripeCount());
            ps.setString(12, account.status().name());
            ps.setObject(13, LocalDate.now());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du compte " + account.code(), e);
        }

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_balance(account_id, stripe_id) VALUES (?,?)")) {
            for (int stripe = 0; stripe < account.stripeCount(); stripe++) {
                ps.setObject(1, account.id());
                ps.setInt(2, stripe);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Amorcage des soldes du compte " + account.code(), e);
        }
    }

    /** Charge en une requete tous les comptes references par une commande. */
    public static Map<UUID, Account> loadAll(Connection c, Collection<UUID> ids) {
        Map<UUID, Account> accounts = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return accounts;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.legal_entity_id, a.code, a.account_kind, a.normal_balance,"
            + " cur.code, cur.scale, cur.rounding_mode,"
            + " a.postable, a.control_available, a.stripe_count, a.status"
            + " FROM account a JOIN currency cur ON cur.code = a.currency"
            + " WHERE a.id = ANY (?)")) {
            Array array = c.createArrayOf("uuid", ids.toArray());
            ps.setArray(1, array);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Account account = new Account(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        AccountKind.valueOf(rs.getString(4)),
                        NormalBalance.valueOf(rs.getString(5)),
                        new CurrencyRef(rs.getString(6), rs.getInt(7),
                                        RoundingMode.valueOf(rs.getString(8))),
                        rs.getBoolean(9),
                        rs.getBoolean(10),
                        rs.getInt(11),
                        AccountStatus.valueOf(rs.getString(12)));
                    accounts.put(account.id(), account);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Chargement des comptes", e);
        }
        return accounts;
    }
}
