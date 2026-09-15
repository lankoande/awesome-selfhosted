package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le reseau d'une entite : son siege, ses agences, leurs comptes de liaison.
 *
 * <p>Le reseau est lu a chaque imputation — il dit ou est le siege, et quels comptes sont des
 * comptes de liaison — et il change quelques fois par an. Il est donc tenu en memoire par
 * entite, et recharge des qu'une agence est creee ici ; une lecture qui ne trouve pas ce qu'elle
 * cherche recharge une fois avant de refuser, pour qu'une agence creee sur une autre instance
 * soit vue sans redemarrage.
 */
public final class Branches {

    private Branches() {}

    public enum Kind { HEAD_OFFICE, REGION, BRANCH }

    public record Branch(UUID id, UUID legalEntityId, String code, String name, Kind kind,
                         UUID parentId, String status, LocalDate openedOn, LocalDate closedOn) {}

    /** Le reseau d'une entite, tel que l'imputation le lit. */
    public record Network(UUID legalEntityId, UUID headOfficeId, Map<UUID, Branch> branches,
                          Map<String, UUID> liaisonAccounts) {

        public Network {
            branches = Map.copyOf(branches);
            liaisonAccounts = Map.copyOf(liaisonAccounts);
        }

        public Set<UUID> liaisonAccountIds() {
            return Set.copyOf(liaisonAccounts.values());
        }

        public Optional<UUID> liaisonAccount(UUID branchId, String currency) {
            return Optional.ofNullable(liaisonAccounts.get(key(branchId, currency)));
        }

        public Branch require(UUID branchId) {
            Branch branch = branches.get(branchId);
            if (branch == null) {
                throw new IllegalArgumentException(
                    "Agence " + branchId + " inconnue de l'entite " + legalEntityId);
            }
            return branch;
        }

        static String key(UUID branchId, String currency) {
            return branchId + "|" + currency;
        }
    }

    private static final ConcurrentHashMap<UUID, Network> CACHE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ creation

    /** Le siege d'une entite : cree avec elle, une fois. */
    public static UUID createHeadOffice(Connection c, UUID legalEntityId, String code, String name,
                                        LocalDate openedOn) {
        UUID id = Ids.newId();
        insert(c, new Branch(id, legalEntityId, code, name, Kind.HEAD_OFFICE, null, "ACTIVE",
                             openedOn, null));
        CACHE.remove(legalEntityId);
        return id;
    }

    /**
     * Cree une agence ou une region, avec ses comptes de liaison par devise.
     *
     * @param parentId        agence de rattachement ; le siege a defaut
     * @param liaisonAccounts compte de liaison par devise ; celui de la devise de tenue de compte
     *                        est obligatoire — sans lui, aucune operation deplacee n'est possible
     */
    public static UUID create(Connection c, UUID legalEntityId, String code, String name, Kind kind,
                              UUID parentId, LocalDate openedOn,
                              Map<CurrencyRef, UUID> liaisonAccounts) {
        Objects.requireNonNull(liaisonAccounts, "liaisonAccounts");
        if (kind == Kind.HEAD_OFFICE) {
            throw new IllegalArgumentException(
                "Le siege est cree avec l'entite ; une entite n'en a qu'un");
        }
        UUID parent = parentId != null ? parentId : headOffice(c, legalEntityId);
        Branch parentBranch = require(c, parent);
        if (!parentBranch.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException(
                "L'agence de rattachement " + parentBranch.code() + " releve d'une autre entite");
        }
        CurrencyRef functional = Entities.functionalCurrency(c, legalEntityId);
        if (liaisonAccounts.keySet().stream().noneMatch(cur -> cur.code().equals(functional.code()))) {
            throw new IllegalArgumentException(
                "L'agence " + code + " n'a pas de compte de liaison en " + functional.code()
                + ", devise de tenue de compte : aucune operation deplacee ne pourrait etre "
                + "equilibree");
        }
        Map<UUID, Account> accounts = Accounts.loadAll(c, liaisonAccounts.values());
        liaisonAccounts.forEach((currency, accountId) -> {
            Account account = accounts.get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("Compte de liaison inconnu : " + accountId);
            }
            if (!account.legalEntityId().equals(legalEntityId)
                || account.kind() != AccountKind.GL || account.hasBranch()
                || !account.postable() || !account.status().acceptsPosting()) {
                throw new IllegalArgumentException(
                    "Le compte " + account.code() + " ne peut pas servir de compte de liaison : "
                    + "il doit etre un compte general imputable de l'entite, sans agence");
            }
            if (!account.currency().code().equals(currency.code())) {
                throw new IllegalArgumentException(
                    "Le compte de liaison " + account.code() + " est en " + account.currency().code()
                    + ", attendu en " + currency.code());
            }
        });

        UUID id = Ids.newId();
        insert(c, new Branch(id, legalEntityId, code, name, kind, parent, "ACTIVE", openedOn, null));
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO branch_liaison(branch_id, currency, account_id) VALUES (?,?,?)")) {
            for (Map.Entry<CurrencyRef, UUID> entry : liaisonAccounts.entrySet()) {
                ps.setObject(1, id);
                ps.setString(2, entry.getKey().code());
                ps.setObject(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Comptes de liaison de l'agence " + code, e);
        }
        CACHE.remove(legalEntityId);
        return id;
    }

    private static void insert(Connection c, Branch branch) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO branch(id, legal_entity_id, code, name, kind, parent_id, status, opened_on)"
            + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, branch.id());
            ps.setObject(2, branch.legalEntityId());
            ps.setString(3, branch.code());
            ps.setString(4, branch.name());
            ps.setString(5, branch.kind().name());
            ps.setObject(6, branch.parentId());
            ps.setString(7, branch.status());
            ps.setObject(8, branch.openedOn());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation de l'agence " + branch.code(), e);
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Le reseau de l'entite, depuis la memoire ou la base. */
    public static Network network(Connection c, UUID legalEntityId) {
        Network network = CACHE.get(legalEntityId);
        return network != null ? network : reload(c, legalEntityId);
    }

    /** Relit le reseau depuis la base et le garde en memoire. */
    public static Network reload(Connection c, UUID legalEntityId) {
        Map<UUID, Branch> branches = new LinkedHashMap<>();
        UUID headOffice = null;
        for (Branch branch : ofEntity(c, legalEntityId)) {
            branches.put(branch.id(), branch);
            if (branch.kind() == Kind.HEAD_OFFICE) {
                headOffice = branch.id();
            }
        }
        if (headOffice == null) {
            throw new LedgerStoreException(
                "L'entite " + legalEntityId + " n'a pas de siege : le reseau n'est pas initialise");
        }
        Map<String, UUID> liaison = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.branch_id, l.currency, l.account_id FROM branch_liaison l"
            + " JOIN branch b ON b.id = l.branch_id WHERE b.legal_entity_id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    liaison.put(Network.key(rs.getObject(1, UUID.class), rs.getString(2).trim()),
                                rs.getObject(3, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des comptes de liaison", e);
        }
        Network network = new Network(legalEntityId, headOffice, branches, liaison);
        CACHE.put(legalEntityId, network);
        return network;
    }

    public static UUID headOffice(Connection c, UUID legalEntityId) {
        return network(c, legalEntityId).headOfficeId();
    }

    public static Branch require(Connection c, UUID branchId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Agence inconnue : " + branchId);
                }
                return read(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'agence " + branchId, e);
        }
    }

    public static List<Branch> ofEntity(Connection c, UUID legalEntityId) {
        List<Branch> branches = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY kind, code")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    branches.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des agences", e);
        }
        return branches;
    }

    /** Oublie le reseau d'une entite ; la prochaine lecture le recharge. */
    public static void invalidate(UUID legalEntityId) {
        CACHE.remove(legalEntityId);
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, code, name, kind, parent_id, status, opened_on, closed_on"
        + " FROM branch";

    private static Branch read(ResultSet rs) throws SQLException {
        return new Branch(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                          rs.getString(3), rs.getString(4), Kind.valueOf(rs.getString(5)),
                          rs.getObject(6, UUID.class), rs.getString(7),
                          rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class));
    }
}
