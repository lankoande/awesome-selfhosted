package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import java.math.RoundingMode;
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
 * Exercices fiscaux.
 *
 * <p>Un exercice porte ses bornes, son statut et son compte de resultat — celui sur lequel la
 * cloture annuelle solde les charges et les produits. Il s'ouvre a deux, comme tout ce qui fixe
 * ce que la banque presente ; il se clot par la cloture annuelle, et se rouvre par son
 * annulation, en le disant.
 */
public final class FiscalYears {

    private FiscalYears() {}

    public record FiscalYear(UUID id, UUID legalEntityId, LocalDate start, LocalDate end,
                             UUID resultAccountId, String status, UUID closedByRunId) {}

    private static final String SELECT = "SELECT id, legal_entity_id, start_date, end_date,"
        + " result_account_id, status, closed_by_run_id FROM fiscal_year";

    /**
     * Ouvre un exercice. Le compte de resultat est un compte general de bilan de l'entite, dans
     * sa devise de tenue de compte : c'est lui qui recoit le resultat, il ne peut pas etre un
     * compte de resultat lui-meme.
     */
    public static UUID open(Connection c, UUID legalEntityId, LocalDate start, LocalDate end,
                            UUID resultAccountId, UUID createdBy, UUID approvedBy) {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(resultAccountId, "resultAccountId");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                "Un exercice se termine apres son debut : du " + start + " au " + end);
        }
        if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
            throw new IllegalArgumentException(
                "Un exercice s'ouvre a deux : le demandeur ne peut pas etre le valideur");
        }
        Account result = Accounts.loadAll(c, Set.of(resultAccountId)).get(resultAccountId);
        if (result == null) {
            throw new IllegalArgumentException("Compte de resultat inconnu : " + resultAccountId);
        }
        CurrencyRef functional = Entities.functionalCurrency(c, legalEntityId);
        if (!result.legalEntityId().equals(legalEntityId) || result.kind() != AccountKind.GL
            || result.nature() != AccountNature.BALANCE_SHEET
            || !result.currency().code().equals(functional.code()) || !result.postable()) {
            throw new IllegalArgumentException(
                "Le compte " + result.code() + " ne peut pas recevoir le resultat : il doit etre "
                + "un compte general de bilan imputable de l'entite, en " + functional.code());
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO fiscal_year(id, legal_entity_id, start_date, end_date,"
            + " result_account_id, created_by, approved_by) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, start);
            ps.setObject(4, end);
            ps.setObject(5, resultAccountId);
            ps.setObject(6, createdBy);
            ps.setObject(7, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Ouverture de l'exercice du " + start + " au " + end
                + " (un exercice ne chevauche pas un autre)", e);
        }
        return id;
    }

    // ------------------------------------------------------------------ lecture

    public static Optional<FiscalYear> endingOn(Connection c, UUID legalEntityId, LocalDate end) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND end_date = ?", legalEntityId, end);
    }

    public static Optional<FiscalYear> covering(Connection c, UUID legalEntityId, LocalDate date) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND ? BETWEEN start_date AND end_date",
                   legalEntityId, date);
    }

    public static List<FiscalYear> ofEntity(Connection c, UUID legalEntityId) {
        List<FiscalYear> years = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY start_date")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    years.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des exercices de l'entite", e);
        }
        return years;
    }

    /** Periodes de l'exercice, hors la derniere, qui ne sont pas closes : ce qui bloque la cloture. */
    public static List<String> periodsNotClosedBefore(Connection c, UUID legalEntityId,
                                                      LocalDate start, LocalDate end) {
        List<String> open = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT start_date, end_date, status FROM accounting_period"
            + " WHERE legal_entity_id = ? AND start_date >= ? AND end_date < ?"
            + "   AND status <> 'CLOSED' ORDER BY start_date")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, start);
            ps.setObject(3, end);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add("du " + rs.getObject(1, LocalDate.class) + " au "
                             + rs.getObject(2, LocalDate.class) + " (" + rs.getString(3) + ")");
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des periodes de l'exercice", e);
        }
        return open;
    }

    // ------------------------------------------------------------------ soldes de resultat

    /**
     * Solde d'un compte de resultat par agence et par devise, en date comptable : positif au
     * debit, negatif au credit. Lu dans le journal, pas dans un cliche : la determination du
     * resultat est exacte par construction.
     */
    public record ProfitAndLossBalance(UUID accountId, UUID branchId, CurrencyRef currency,
                                       Money signedBalance) {}

    public static List<ProfitAndLossBalance> profitAndLossBalances(Connection c,
                                                                   UUID legalEntityId,
                                                                   LocalDate through) {
        List<ProfitAndLossBalance> balances = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.account_id, l.branch_id, cur.code, cur.scale, cur.rounding_mode,"
            + "       SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount ELSE -l.amount END)"
            + "  FROM journal_line l JOIN account a ON a.id = l.account_id"
            + "  JOIN currency cur ON cur.code = l.currency"
            + " WHERE l.legal_entity_id = ? AND a.nature = 'PROFIT_AND_LOSS'"
            + "   AND l.booking_date <= ?"
            + " GROUP BY l.account_id, l.branch_id, cur.code, cur.scale, cur.rounding_mode"
            + " HAVING SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount ELSE -l.amount END) <> 0"
            + " ORDER BY cur.code, l.branch_id, l.account_id")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, through);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(3), rs.getInt(4),
                                                           RoundingMode.valueOf(rs.getString(5)));
                    balances.add(new ProfitAndLossBalance(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), currency,
                        Money.of(rs.getBigDecimal(6), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des soldes de resultat", e);
        }
        return balances;
    }

    // ------------------------------------------------------------------ cloture

    public static void close(Connection c, UUID fiscalYearId, UUID runId) {
        update(c, "UPDATE fiscal_year SET status = 'CLOSED', closed_by_run_id = ? WHERE id = ?"
                  + " AND status <> 'CLOSED'", runId, fiscalYearId, "Cloture de l'exercice");
    }

    /** Rouvre un exercice clos ; REOPENED n'est pas OPEN : l'annulation laisse une trace. */
    public static void reopen(Connection c, UUID fiscalYearId) {
        update(c, "UPDATE fiscal_year SET status = 'REOPENED', closed_by_run_id = ? WHERE id = ?"
                  + " AND status = 'CLOSED'", null, fiscalYearId, "Reouverture de l'exercice");
    }

    private static void update(Connection c, String sql, UUID runId, UUID fiscalYearId,
                               String what) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, runId);
            ps.setObject(2, fiscalYearId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(what + " : exercice " + fiscalYearId
                                                + " introuvable ou deja dans cet etat");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException(what, e);
        }
    }

    // ------------------------------------------------------------------ interne

    private static Optional<FiscalYear> one(Connection c, String sql, UUID legalEntityId,
                                            LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de l'exercice", e);
        }
    }

    private static FiscalYear read(ResultSet rs) throws SQLException {
        return new FiscalYear(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                              rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class),
                              rs.getObject(5, UUID.class), rs.getString(6),
                              rs.getObject(7, UUID.class));
    }
}
