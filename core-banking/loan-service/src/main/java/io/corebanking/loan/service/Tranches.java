package io.corebanking.loan.service;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.DisbursementPlan;
import io.corebanking.loan.InterimInterest;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.Tranche;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acces au plan de deblocage, a l'etat de mobilisation et aux interets intercalaires.
 *
 * <p>Le <b>montant mobilise n'est pas stocke</b> : il se lit sur les tranches debloquees. Le
 * denormaliser ferait exister deux verites sur le capital, et rien ne garantirait que celle qui
 * commande l'echeancier soit la bonne.
 */
public final class Tranches {

    private Tranches() {}

    public enum Status { PLANNED, RELEASED, CANCELLED }

    // ------------------------------------------------------------------ ouverture

    /**
     * Ouvre la mobilisation d'un credit : enregistre le plan, la duree accordee et la date limite.
     *
     * <p>Aucun echeancier n'est publie. Tant qu'une tranche reste a debloquer, le capital a amortir
     * n'est pas connu, et un echeancier publie a ce stade reclamerait l'amortissement d'un capital
     * non verse.
     */
    public static void openMobilisation(Connection c, UUID contractId, DisbursementPlan plan,
                                        LoanTerms terms, Money upfrontFees, UUID openedBy,
                                        UUID approvedBy) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_mobilisation(contract_id, drawdown_deadline, instalment_count,"
            + " grace_instalments, first_due_date, upfront_fees, interim_billed_through,"
            + " opened_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, contractId);
            ps.setObject(2, plan.drawdownDeadline());
            ps.setInt(3, terms.instalmentCount());
            ps.setInt(4, terms.graceInstalments());
            ps.setObject(5, terms.firstDueDate());
            ps.setBigDecimal(6, upfrontFees.amount());
            // La veille du premier jour du credit : rien n'est encore couvert, et la premiere
            // periode intercalaire s'ouvrira donc au jour du contrat.
            ps.setObject(7, terms.disbursedOn().minusDays(1));
            ps.setObject(8, openedBy);
            ps.setObject(9, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Ouverture de la mobilisation du credit " + contractId,
                                           e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_tranche(id, contract_id, number, planned_on, planned_amount,"
            + " condition_label) VALUES (?,?,?,?,?,?)")) {
            for (Tranche tranche : plan.tranches()) {
                ps.setObject(1, Ids.newId());
                ps.setObject(2, contractId);
                ps.setInt(3, tranche.number());
                ps.setObject(4, tranche.plannedOn());
                ps.setBigDecimal(5, tranche.amount().amount());
                ps.setString(6, tranche.condition());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du plan de deblocage du credit "
                                           + contractId, e);
        }
    }

    // ------------------------------------------------------------------ plan

    /** Une tranche, telle qu'elle est conservee. */
    public record TrancheRow(UUID id, int number, LocalDate plannedOn, Money plannedAmount,
                             String condition, Status status, LocalDate releasedOn,
                             Money releasedAmount) {}

    public static List<TrancheRow> of(Connection c, UUID contractId, CurrencyRef currency) {
        List<TrancheRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, number, planned_on, planned_amount, condition_label, status, released_on,"
            + " released_amount FROM loan_tranche WHERE contract_id = ? ORDER BY number")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.math.BigDecimal released = rs.getBigDecimal(8);
                    rows.add(new TrancheRow(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, LocalDate.class),
                        Money.of(rs.getBigDecimal(4), currency), rs.getString(5),
                        Status.valueOf(rs.getString(6)), rs.getObject(7, LocalDate.class),
                        released == null ? null : Money.of(released, currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du plan de deblocage du credit " + contractId,
                                           e);
        }
        return rows;
    }

    public static void markReleased(Connection c, UUID trancheId, LocalDate on, Money amount,
                                    UUID entryId, UUID releasedBy, UUID approvedBy) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_tranche SET status = 'RELEASED', released_on = ?, released_amount = ?,"
            + " entry_id = ?, released_by = ?, approved_by = ?"
            + " WHERE id = ? AND status = 'PLANNED'")) {
            ps.setObject(1, on);
            ps.setBigDecimal(2, amount.amount());
            ps.setObject(3, entryId);
            ps.setObject(4, releasedBy);
            ps.setObject(5, approvedBy);
            ps.setObject(6, trancheId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Tranche " + trancheId + " introuvable ou deja sortie de l'etat prevu : les "
                    + "fonds ne se versent pas deux fois.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Deblocage de la tranche " + trancheId, e);
        }
    }

    public static void cancel(Connection c, UUID trancheId, LocalDate on, String reason,
                              UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_tranche SET status = 'CANCELLED', cancelled_on = ?,"
            + " cancellation_reason = ?, cancelled_run_id = ?"
            + " WHERE id = ? AND status = 'PLANNED'")) {
            ps.setObject(1, on);
            ps.setString(2, reason);
            ps.setObject(3, batchRunId);
            ps.setObject(4, trancheId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation de la tranche " + trancheId, e);
        }
    }

    // ------------------------------------------------------------------ mobilisation

    /** Etat d'une mobilisation. */
    public record MobilisationRow(UUID contractId, LocalDate deadline, int instalmentCount,
                                  int graceInstalments, LocalDate firstDueDate, Money upfrontFees,
                                  LocalDate billedThrough, LocalDate closedOn, UUID openedBy,
                                  UUID approvedBy) {

        public boolean open() {
            return closedOn == null;
        }
    }

    public static Optional<MobilisationRow> mobilisationOf(Connection c, UUID contractId,
                                                           CurrencyRef currency) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT drawdown_deadline, instalment_count, grace_instalments, first_due_date,"
            + " upfront_fees, interim_billed_through, closed_on, opened_by, approved_by"
            + "  FROM loan_mobilisation WHERE contract_id = ?")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MobilisationRow(
                    contractId, rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3),
                    rs.getObject(4, LocalDate.class), Money.of(rs.getBigDecimal(5), currency),
                    rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                    rs.getObject(8, UUID.class), rs.getObject(9, UUID.class)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la mobilisation du credit " + contractId, e);
        }
    }

    /** Credits d'une entite dont la mobilisation reste ouverte, dans un ordre stable. */
    public static List<UUID> openMobilisations(Connection c, UUID legalEntityId) {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT m.contract_id FROM loan_mobilisation m"
            + "  JOIN loan_contract l ON l.id = m.contract_id"
            + " WHERE l.legal_entity_id = ? AND l.status = 'ACTIVE' AND m.closed_on IS NULL"
            + " ORDER BY m.contract_id")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recensement des mobilisations en cours", e);
        }
        return ids;
    }

    /** Mises a disposition du contrat, dans l'ordre des dates de valeur. */
    public static List<InterimInterest.Drawing> drawings(Connection c, UUID contractId,
                                                         CurrencyRef currency) {
        List<InterimInterest.Drawing> drawings = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT released_on, released_amount FROM loan_tranche"
            + " WHERE contract_id = ? AND status = 'RELEASED' ORDER BY released_on, number")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    drawings.add(new InterimInterest.Drawing(
                        rs.getObject(1, LocalDate.class),
                        Money.of(rs.getBigDecimal(2), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des deblocages du credit " + contractId, e);
        }
        return drawings;
    }

    /** Capital mobilise : la somme de ce qui a reellement ete verse. */
    public static Money drawn(Connection c, UUID contractId, CurrencyRef currency) {
        Money total = Money.zero(currency);
        for (InterimInterest.Drawing drawing : drawings(c, contractId, currency)) {
            total = total.plus(drawing.amount());
        }
        return total;
    }

    public static void billedThrough(Connection c, UUID contractId, LocalDate through) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_mobilisation SET interim_billed_through = ?"
            + " WHERE contract_id = ? AND interim_billed_through < ?")) {
            ps.setObject(1, through);
            ps.setObject(2, contractId);
            ps.setObject(3, through);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Avancement des interets intercalaires du credit "
                                           + contractId, e);
        }
    }

    public static void close(Connection c, UUID contractId, LocalDate on, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE loan_mobilisation SET closed_on = ?, closed_run_id = ?"
            + " WHERE contract_id = ? AND closed_on IS NULL")) {
            ps.setObject(1, on);
            ps.setObject(2, batchRunId);
            ps.setObject(3, contractId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Mobilisation du credit " + contractId + " deja close : l'echeancier definitif"
                    + " ne s'arrete pas deux fois.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture de la mobilisation du credit " + contractId, e);
        }
    }

    // ------------------------------------------------------------------ interets intercalaires

    public static UUID recordInterim(Connection c, UUID contractId,
                                     InterimInterest.Accrual accrual, Money tax, UUID receivableId,
                                     UUID entryId, UUID batchRunId) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO loan_interim_interest(id, contract_id, period_start, period_end,"
            + " drawn_at_end, interest, tax, receivable_id, entry_id, batch_run_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, contractId);
            ps.setObject(3, accrual.from());
            ps.setObject(4, accrual.toInclusive());
            ps.setBigDecimal(5, accrual.drawnAtEnd().amount());
            ps.setBigDecimal(6, accrual.interest().amount());
            ps.setBigDecimal(7, tax.amount());
            ps.setObject(8, receivableId);
            ps.setObject(9, entryId);
            ps.setObject(10, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement des interets intercalaires du credit "
                                           + contractId, e);
        }
        return id;
    }

    /** Periodes intercalaires facturees, pour le calcul du taux effectif a la cloture. */
    public record InterimPeriod(LocalDate periodEnd, Money interest, Money tax) {}

    public static List<InterimPeriod> interimPeriods(Connection c, UUID contractId,
                                                     CurrencyRef currency) {
        List<InterimPeriod> periods = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT period_end, interest, tax FROM loan_interim_interest"
            + " WHERE contract_id = ? AND status = 'ACTIVE' ORDER BY period_end")) {
            ps.setObject(1, contractId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    periods.add(new InterimPeriod(rs.getObject(1, LocalDate.class),
                                                  Money.of(rs.getBigDecimal(2), currency),
                                                  Money.of(rs.getBigDecimal(3), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des periodes intercalaires du credit "
                                           + contractId, e);
        }
        return periods;
    }
}
