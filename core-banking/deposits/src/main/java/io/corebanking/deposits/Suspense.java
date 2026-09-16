package io.corebanking.deposits;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
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
import java.util.UUID;

/**
 * Suspens : ce qui attend le correspondant, avec son anciennete et son responsable.
 *
 * <p>Un suspens est un ordre de paiement ordonne ou envoye mais non regle, une remise de cheque
 * non encaissee, un prelevement execute mais non regle, ou un compte d'attente dont le solde
 * n'est pas nul. Chacun a une <b>anciennete</b> en jours ouvres — depuis l'ordre, la remise,
 * l'execution, ou depuis le dernier jour ou le compte d'attente etait solde — et une
 * <b>politique</b> par nature dit au-dela de combien de jours il est en retard, et qui en
 * repond. La revue de l'arrete remonte les retards ; un compte d'attente en retard bloque la
 * journee, parce que sa justification conditionne la sincerite de l'arrete.
 *
 * <p>Sans politique pour une nature, ses suspens sont listes sans etre en retard : le
 * parametrage dit ce qu'il tolere, le systeme ne le presume pas.
 */
public final class Suspense {

    private Suspense() {}

    public enum Kind { SUSPENSE_ACCOUNT, PAYMENT_ORDER, CHEQUE_DEPOSIT, DIRECT_DEBIT }

    public record Policy(UUID id, UUID legalEntityId, Kind kind, int maxBusinessDays, String owner,
                         LocalDate validFrom, LocalDate validTo, UUID createdBy, UUID approvedBy) {
        public boolean covers(LocalDate date) {
            return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
        }
    }

    public record Draft(UUID legalEntityId, Kind kind, int maxBusinessDays, String owner,
                        LocalDate validFrom, LocalDate validTo, UUID createdBy, UUID approvedBy) {
        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(validFrom, "validFrom");
            if (maxBusinessDays < 0) {
                throw new IllegalArgumentException("L'anciennete toleree est un nombre de jours ouvres positif ou nul");
            }
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("Un suspens a un responsable");
            }
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Une politique ne finit pas avant de commencer");
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException(
                    "Une politique de suspens se fixe a deux : le demandeur ne peut pas etre le valideur");
            }
        }
    }

    /**
     * Un suspens tel que la revue le voit.
     *
     * @param reference       l'objet en suspens : l'ordre, la remise, le prelevement, le compte
     * @param since           depuis quand il attend
     * @param ageBusinessDays son anciennete en jours ouvres a la date de la revue
     * @param maxBusinessDays l'anciennete toleree par la politique, ou nul sans politique
     * @param owner           le responsable, ou nul sans politique
     */
    public record Item(Kind kind, UUID reference, UUID accountId, String accountCode, Money amount,
                       LocalDate since, int ageBusinessDays, Integer maxBusinessDays, String owner,
                       boolean overdue) {}

    // ------------------------------------------------------------------ politiques

    public static Policy setPolicy(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO suspense_policy(id, legal_entity_id, kind, max_business_days, owner,"
            + " valid_from, valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.kind().name());
            ps.setInt(4, draft.maxBusinessDays());
            ps.setString(5, draft.owner().trim());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.validTo());
            ps.setObject(8, draft.createdBy());
            ps.setObject(9, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                throw new IllegalStateException("Une politique de suspens couvre deja la nature "
                    + draft.kind() + " sur une partie de la periode", e);
            }
            throw new LedgerStoreException("Enregistrement de la politique de suspens", e);
        }
        return policies(c, draft.legalEntityId()).stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow();
    }

    public static List<Policy> policies(Connection c, UUID legalEntityId) {
        List<Policy> policies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, kind, max_business_days, owner, valid_from, valid_to,"
            + " created_by, approved_by FROM suspense_policy WHERE legal_entity_id = ?"
            + " ORDER BY kind, valid_from")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    policies.add(new Policy(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        Kind.valueOf(rs.getString(3)), rs.getInt(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, UUID.class), rs.getObject(9, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des politiques de suspens", e);
        }
        return policies;
    }

    /** La politique d'une nature a une date, s'il y en a une. */
    public static Optional<Policy> policyFor(Connection c, UUID legalEntityId, Kind kind,
                                             LocalDate on) {
        return policies(c, legalEntityId).stream()
            .filter(p -> p.kind() == kind && p.covers(on)).findFirst();
    }

    // ------------------------------------------------------------------ revue

    /** Les suspens de l'entite a la date, du plus ancien au plus recent, avec leur retard. */
    public static List<Item> items(Connection c, UUID legalEntityId, LocalDate on,
                                   BusinessCalendar calendar) {
        List<Item> items = new ArrayList<>();
        Map<Kind, Policy> policies = new java.util.EnumMap<>(Kind.class);
        for (Policy policy : policies(c, legalEntityId)) {
            if (policy.covers(on)) {
                policies.put(policy.kind(), policy);
            }
        }
        collect(c, items, Kind.PAYMENT_ORDER, policies, on, calendar,
            "SELECT p.id, p.clearing_account_id, a.code, p.amount, p.ordered_on,"
            + " cur.code, cur.scale, cur.rounding_mode"
            + " FROM payment_order p JOIN account a ON a.id = p.clearing_account_id"
            + " JOIN currency cur ON cur.code = p.currency"
            + " WHERE p.legal_entity_id = ? AND p.status IN ('ORDERED','SENT')",
            legalEntityId);
        collect(c, items, Kind.CHEQUE_DEPOSIT, policies, on, calendar,
            "SELECT d.id, d.collection_account_id, a.code, d.amount, d.deposited_on,"
            + " cur.code, cur.scale, cur.rounding_mode"
            + " FROM cheque_deposit d JOIN account a ON a.id = d.collection_account_id"
            + " JOIN currency cur ON cur.code = d.currency"
            + " WHERE d.legal_entity_id = ? AND d.status = 'DEPOSITED'",
            legalEntityId);
        collect(c, items, Kind.DIRECT_DEBIT, policies, on, calendar,
            "SELECT d.id, d.clearing_account_id, a.code, d.amount, d.executed_on,"
            + " cur.code, cur.scale, cur.rounding_mode"
            + " FROM direct_debit d JOIN account a ON a.id = d.clearing_account_id"
            + " JOIN currency cur ON cur.code = d.currency"
            + " WHERE d.legal_entity_id = ? AND d.status = 'COLLECTED'",
            legalEntityId);
        // Un compte d'attente non solde attend depuis son premier mouvement posterieur au dernier
        // jour ou il etait solde — depuis son premier mouvement, s'il ne l'a jamais ete.
        collect(c, items, Kind.SUSPENSE_ACCOUNT, policies, on, calendar,
            "SELECT a.id, a.id, a.code, SUM(b.balance), COALESCE("
            + "   (SELECT MIN(l.booking_date) FROM journal_line l"
            + "     WHERE l.account_id = a.id AND l.booking_date > COALESCE("
            + "       (SELECT MAX(d.business_date) FROM account_balance_daily d"
            + "         WHERE d.account_id = a.id AND d.closing_balance = 0), DATE '0001-01-01')),"
            + "   a.opened_at),"
            + " cur.code, cur.scale, cur.rounding_mode"
            + " FROM account a JOIN account_balance b ON b.account_id = a.id"
            + " JOIN currency cur ON cur.code = a.currency"
            + " WHERE a.legal_entity_id = ? AND a.account_kind = 'SUSPENSE'"
            + " GROUP BY a.id, a.code, a.opened_at, cur.code, cur.scale, cur.rounding_mode"
            + " HAVING SUM(b.balance) <> 0",
            legalEntityId);
        items.sort(java.util.Comparator.comparing(Item::since).thenComparing(i -> i.reference().toString()));
        return items;
    }

    private static void collect(Connection c, List<Item> items, Kind kind, Map<Kind, Policy> policies,
                                LocalDate on, BusinessCalendar calendar, String sql,
                                UUID legalEntityId) {
        Policy policy = policies.get(kind);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(6), rs.getInt(7),
                                                           RoundingMode.valueOf(rs.getString(8)));
                    LocalDate since = rs.getObject(5, LocalDate.class);
                    int age = since.isBefore(on) ? calendar.businessDaysBetween(since, on) : 0;
                    boolean overdue = policy != null && age > policy.maxBusinessDays();
                    items.add(new Item(kind, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), Money.of(rs.getBigDecimal(4), currency), since, age,
                        policy == null ? null : policy.maxBusinessDays(),
                        policy == null ? null : policy.owner(), overdue));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Revue des suspens : " + kind, e);
        }
    }

    /** Les comptes d'attente en retard, ou non soldes sans politique : ce qui bloque la journee. */
    public static List<Item> blockingSuspenseAccounts(Connection c, UUID legalEntityId, LocalDate on,
                                                      BusinessCalendar calendar) {
        List<Item> blocking = new ArrayList<>();
        for (Item item : items(c, legalEntityId, on, calendar)) {
            if (item.kind() == Kind.SUSPENSE_ACCOUNT && (item.maxBusinessDays() == null || item.overdue())) {
                blocking.add(item);
            }
        }
        return blocking;
    }
}
