package io.corebanking.deposits;

import io.corebanking.kernel.id.Ids;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Caisses par guichetier.
 *
 * <p>Une caisse est un compte interne d'agence tenu par un guichetier. Le guichetier ne choisit
 * pas sa caisse : elle est la sienne, resolue depuis son identite. Une journee de caisse se
 * termine par un arrete ({@link TillService}) ; une caisse arretee ne sert plus ce jour-la, et une
 * caisse mouvementee non arretee bloque l'arrete de la banque.
 */
public final class Tills {

    private Tills() {}

    public record Till(UUID id, UUID legalEntityId, UUID branchId, String code, UUID cashAccountId,
                       String tellerSubjectId, UUID differenceAccountId, String status) {}

    /**
     * @param tellerSubjectId     sujet du guichetier titulaire, tel que le porte son jeton ; nul
     *                            pour une caisse sans titulaire (coffre, caisse centrale)
     * @param differenceAccountId compte des ecarts de caisse ; sans lui, un ecart a l'arrete est
     *                            refuse, jamais ajuste
     */
    public record Draft(UUID legalEntityId, String code, UUID cashAccountId, String tellerSubjectId,
                        UUID differenceAccountId, UUID createdBy, UUID approvedBy) {}

    private static final String SELECT = "SELECT id, legal_entity_id, branch_id, code,"
        + " cash_account_id, teller_subject_id, difference_account_id, status FROM till";

    // ------------------------------------------------------------------ creation

    /**
     * Cree une caisse, a deux : elle affecte un compte de la banque a une personne. Le compte de
     * caisse est un compte interne imputable de l'entite, rattache a une agence — celle de la
     * caisse ; le compte d'ecart est un compte de la banque dans la devise de la caisse.
     */
    public static UUID create(Connection c, Draft draft) {
        Objects.requireNonNull(draft.legalEntityId(), "legalEntityId");
        if (draft.code() == null || draft.code().isBlank()) {
            throw new IllegalArgumentException("Code de caisse obligatoire");
        }
        if (draft.cashAccountId() == null) {
            throw new IllegalArgumentException("Compte de caisse obligatoire");
        }
        if (draft.createdBy() == null || draft.approvedBy() == null
            || draft.approvedBy().equals(draft.createdBy())) {
            throw new IllegalArgumentException(
                "Une caisse se cree a deux : le demandeur ne peut pas etre le valideur");
        }
        Account cash = account(c, draft.cashAccountId(), "Compte de caisse");
        if (!cash.legalEntityId().equals(draft.legalEntityId())
            || cash.kind() != AccountKind.INTERNAL || !cash.hasBranch() || !cash.postable()
            || !cash.status().acceptsPosting()) {
            throw new IllegalArgumentException(
                "Le compte " + cash.code() + " ne peut pas servir de caisse : il doit etre un "
                + "compte interne imputable de l'entite, rattache a une agence");
        }
        if (draft.differenceAccountId() != null) {
            Account difference = account(c, draft.differenceAccountId(), "Compte d'ecart");
            if (!difference.legalEntityId().equals(draft.legalEntityId())
                || difference.kind() == AccountKind.CUSTOMER
                || !difference.currency().equals(cash.currency())
                || !difference.postable() || !difference.status().acceptsPosting()) {
                throw new IllegalArgumentException(
                    "Le compte d'ecart " + difference.code() + " doit etre un compte imputable de "
                    + "la banque, dans la devise de la caisse (" + cash.currency().code() + ")");
            }
            if (difference.hasBranch() && !difference.branchId().equals(cash.branchId())) {
                throw new IllegalArgumentException(
                    "Le compte d'ecart " + difference.code() + " releve d'une autre agence que "
                    + "la caisse");
            }
        }
        forCashAccount(c, cash.id()).ifPresent(existing -> {
            throw new IllegalStateException(
                "Le compte " + cash.code() + " est deja la caisse " + existing.code());
        });
        if (draft.tellerSubjectId() != null) {
            forTeller(c, draft.legalEntityId(), draft.tellerSubjectId()).ifPresent(existing -> {
                throw new IllegalStateException(
                    "Le guichetier " + draft.tellerSubjectId() + " tient deja la caisse "
                    + existing.code() + " : une caisse par guichetier");
            });
        }
        if (byCode(c, draft.legalEntityId(), draft.code()).isPresent()) {
            throw new IllegalStateException("La caisse " + draft.code() + " existe deja");
        }

        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO till(id, legal_entity_id, branch_id, code, cash_account_id,"
            + " teller_subject_id, difference_account_id, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setObject(3, cash.branchId());
            ps.setString(4, draft.code());
            ps.setObject(5, cash.id());
            ps.setString(6, draft.tellerSubjectId());
            ps.setObject(7, draft.differenceAccountId());
            ps.setObject(8, draft.createdBy());
            ps.setObject(9, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation de la caisse " + draft.code(), e);
        }
        return id;
    }

    private static Account account(Connection c, UUID accountId, String role) {
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException(role + " inconnu : " + accountId);
        }
        return account;
    }

    // ------------------------------------------------------------------ lecture

    public static Optional<Till> find(Connection c, UUID tillId) {
        return one(c, SELECT + " WHERE id = ?", ps -> ps.setObject(1, tillId));
    }

    public static Till require(Connection c, UUID tillId) {
        return find(c, tillId).orElseThrow(() -> new UnknownTillException(tillId));
    }

    /** La caisse active d'un guichetier, resolue depuis le sujet de son jeton. */
    public static Optional<Till> forTeller(Connection c, UUID legalEntityId, String subjectId) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND teller_subject_id = ?"
                      + " AND status = 'ACTIVE'",
                   ps -> { ps.setObject(1, legalEntityId); ps.setString(2, subjectId); });
    }

    public static Optional<Till> forCashAccount(Connection c, UUID cashAccountId) {
        return one(c, SELECT + " WHERE cash_account_id = ?", ps -> ps.setObject(1, cashAccountId));
    }

    private static Optional<Till> byCode(Connection c, UUID legalEntityId, String code) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND code = ?",
                   ps -> { ps.setObject(1, legalEntityId); ps.setString(2, code); });
    }

    /** Les caisses d'une entite, dans l'ordre de leur code. */
    public static List<Till> ofEntity(Connection c, UUID legalEntityId) {
        List<Till> tills = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY code")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tills.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des caisses de l'entite", e);
        }
        return tills;
    }

    // ------------------------------------------------------------------ journee de caisse

    public static boolean closedOn(Connection c, UUID tillId, LocalDate businessDate) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM till_closure WHERE till_id = ? AND business_date = ?")) {
            ps.setObject(1, tillId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'arrete de caisse", e);
        }
    }

    /**
     * Refuse une operation sur une caisse arretee pour la journee. Un compte de caisse qui
     * n'est la caisse de personne n'est pas concerne : il n'a pas de journee a arreter.
     */
    public static void requireOpenOn(Connection c, UUID cashAccountId, LocalDate businessDate) {
        forCashAccount(c, cashAccountId).ifPresent(till -> {
            if (closedOn(c, till.id(), businessDate)) {
                throw new TillClosedException(till.code(), businessDate);
            }
        });
    }

    /**
     * Caisses mouvementees a la date et non arretees : ce qui bloque l'arrete de la banque. Une
     * caisse qui n'a pas servi n'a rien a arreter.
     */
    public static List<String> movedAndUnclosed(Connection c, UUID legalEntityId,
                                                LocalDate businessDate) {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT t.code FROM till t"
            + " WHERE t.legal_entity_id = ? AND t.status = 'ACTIVE'"
            + "   AND EXISTS (SELECT 1 FROM journal_line l"
            + "                WHERE l.account_id = t.cash_account_id AND l.booking_date = ?)"
            + "   AND NOT EXISTS (SELECT 1 FROM till_closure k"
            + "                    WHERE k.till_id = t.id AND k.business_date = ?)"
            + " ORDER BY t.code")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            ps.setObject(3, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des caisses non arretees", e);
        }
        return codes;
    }

    static UUID recordClosure(Connection c, UUID tillId, LocalDate businessDate, Money counted,
                              Money book, Money difference, UUID entryId, UUID closedBy) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO till_closure(id, till_id, business_date, counted, book, difference,"
            + " entry_id, closed_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tillId);
            ps.setObject(3, businessDate);
            ps.setBigDecimal(4, counted.amount());
            ps.setBigDecimal(5, book.amount());
            ps.setBigDecimal(6, difference.amount());
            ps.setObject(7, entryId);
            ps.setObject(8, closedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'arrete de caisse", e);
        }
        return id;
    }

    // ------------------------------------------------------------------ interne

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Optional<Till> one(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture d'une caisse", e);
        }
    }

    private static Till read(ResultSet rs) throws SQLException {
        return new Till(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, UUID.class),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8));
    }

    /** Caisse inconnue — ou d'une autre entite, que le cloisonnement ne montre pas. */
    public static class UnknownTillException extends RuntimeException {
        public UnknownTillException(UUID tillId) {
            super("Caisse inconnue : " + tillId);
        }
    }

    /** La journee de cette caisse est arretee : elle ne sert plus ce jour-la. */
    public static class TillClosedException extends RuntimeException {
        public TillClosedException(String code, LocalDate businessDate) {
            super("La caisse " + code + " est arretee pour le " + businessDate
                  + " : elle ne sert plus ce jour-la.");
        }
    }
}
