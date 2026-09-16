package io.corebanking.loan.service;

import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.Reconciliation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sous-livre du credit contre le grand livre.
 *
 * <p>Trois rapprochements, tous sur les positions ouvertes et jamais sur l'historique :
 *
 * <ul>
 *   <li><b>Creances rattachees</b> — la somme des creances ouvertes hors capital, par compte de
 *       creances du produit, egale le solde de ce compte.</li>
 *   <li><b>Encours</b> — pour chaque credit, le solde du compte de pret egale le capital restant
 *       du selon l'echeancier en vigueur, plus le capital echu non regle ; pour un credit en
 *       mobilisation, le total des tranches versees.</li>
 *   <li><b>Interets courus</b> — la somme des courus des echeances en cours, par compte de
 *       courus, egale le solde de ce compte.</li>
 * </ul>
 *
 * <p>C'est la classe de defaut que les tests ont trouvee deux fois — une creance sans ecriture,
 * une ecriture sans creance — et que le grand livre, equilibre, ne voit jamais.
 */
public final class LoanReconciliation implements Reconciliation.Check {

    public static final String CHECK_RECEIVABLES = "SOUS_LIVRE_CREANCES_CREDIT";
    public static final String CHECK_OUTSTANDING = "SOUS_LIVRE_ENCOURS_CREDIT";
    public static final String CHECK_ACCRUED = "SOUS_LIVRE_ICNE_CREDIT";
    public static final String CHECK_WRITTEN_OFF = "HORS_BILAN_CREANCES_EN_PERTE";

    @Override
    public List<Reconciliation.Discrepancy> run(Connection c, UUID legalEntityId,
                                                LocalDate businessDate, UUID runId) {
        List<Reconciliation.Discrepancy> all = new ArrayList<>();
        all.addAll(receivables(c, legalEntityId, businessDate));
        all.addAll(outstanding(c, legalEntityId));
        all.addAll(accruedInterest(c, legalEntityId, businessDate));
        all.addAll(writtenOff(c, legalEntityId, businessDate));
        return all;
    }

    /**
     * Comptes generaux designes par un parametre des versions de produit de credit en vigueur :
     * un compte de creances ou de courus sans plus aucun credit doit etre a zero, et ce n'est
     * verifiable que si on le nomme.
     */
    private static final String PRODUCT_ACCOUNTS =
        "SELECT DISTINCT v.code AS product_code, pp.value::uuid AS account_id"
        + "  FROM product_version v"
        + "  JOIN product_parameter pp ON pp.product_version_id = v.id AND pp.name = ?"
        + " WHERE v.legal_entity_id = ? AND v.status = 'ACTIVE'"
        + "   AND v.valid_from <= ? AND (v.valid_to IS NULL OR v.valid_to >= ?)";

    public static List<Reconciliation.Discrepancy> receivables(Connection c, UUID legalEntityId,
                                                               LocalDate businessDate) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "WITH gl AS (" + PRODUCT_ACCOUNTS + "),"
            + " expected AS ("
            + "   SELECT gl.account_id, COALESCE(SUM(r.outstanding), 0) AS amount"
            + "     FROM gl"
            + "     LEFT JOIN loan_contract k ON k.legal_entity_id = ?"
            + "                              AND k.product_code = gl.product_code"
            + "                              AND k.status = 'ACTIVE'"
            + "     LEFT JOIN loan_receivable r ON r.contract_id = k.id AND NOT r.cancelled"
            + "                                AND r.outstanding > 0"
            + "                                AND r.category NOT IN ('PRINCIPAL','FUTURE_PRINCIPAL')"
            + "    GROUP BY gl.account_id)"
            + " SELECT e.account_id, e.amount, COALESCE(b.balance, 0)"
            + "   FROM expected e LEFT JOIN account_balance_agg b ON b.account_id = e.account_id"
            + "  WHERE e.amount <> COALESCE(b.balance, 0)")) {
            ps.setString(1, LoanCatalog.P_ACCRUED);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, businessDate);
            ps.setObject(4, businessDate);
            ps.setObject(5, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK_RECEIVABLES, rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des creances de credit", e);
        }
        return discrepancies;
    }

    public static List<Reconciliation.Discrepancy> outstanding(Connection c, UUID legalEntityId) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT x.reference, x.expected, x.actual FROM ("
            + "   SELECT k.reference,"
            + "          CASE WHEN s.id IS NULL THEN"
            + "               COALESCE((SELECT SUM(t.released_amount) FROM loan_tranche t"
            + "                          WHERE t.contract_id = k.id AND t.status = 'RELEASED'), 0)"
            + "          ELSE COALESCE((SELECT l.outstanding_before FROM loan_schedule_line l"
            + "                          WHERE l.schedule_id = s.id AND l.made_due_on IS NULL"
            + "                          ORDER BY l.number LIMIT 1), 0)"
            + "             + COALESCE((SELECT SUM(r.outstanding) FROM loan_receivable r"
            + "                          WHERE r.contract_id = k.id AND NOT r.cancelled"
            + "                            AND r.category IN ('PRINCIPAL','FUTURE_PRINCIPAL')), 0)"
            + "          END AS expected,"
            + "          COALESCE(b.balance, 0) AS actual"
            + "     FROM loan_contract k"
            + "     LEFT JOIN loan_schedule s ON s.contract_id = k.id AND s.superseded_on IS NULL"
            + "     LEFT JOIN account_balance_agg b ON b.account_id = k.loan_account_id"
            + "    WHERE k.legal_entity_id = ? AND k.status = 'ACTIVE') x"
            + " WHERE x.expected <> x.actual")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK_OUTSTANDING, rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des encours de credit", e);
        }
        return discrepancies;
    }

    /**
     * Le hors bilan des creances passees en perte contre ce qui reste du.
     *
     * <p>Une creance sortie de l'actif reste due : le compte de hors bilan doit porter, au
     * centime, ce qui a ete passe en perte et pas encore recouvre. Un ecart signale soit un
     * recouvrement encaisse sans sortir du hors bilan — l'engagement survivrait a la dette —,
     * soit une sortie oubliee, et le suivi du recouvrement porterait sur du vide.
     */
    public static List<Reconciliation.Discrepancy> writtenOff(Connection c, UUID legalEntityId,
                                                              LocalDate businessDate) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.product_code, COALESCE(w.expected, 0), COALESCE(b.balance, 0)"
            + "  FROM (" + PRODUCT_ACCOUNTS + ") a"
            + "  LEFT JOIN (SELECT k.product_code,"
            + "                    SUM(x.principal_written + x.receivables_written)"
            + "                  - SUM(COALESCE((SELECT SUM(r.amount) FROM loan_recovery r"
            + "                                   WHERE r.write_off_id = x.id), 0)) AS expected"
            + "               FROM loan_write_off x"
            + "               JOIN loan_contract k ON k.id = x.contract_id"
            + "              WHERE x.legal_entity_id = ?"
            + "              GROUP BY k.product_code) w ON w.product_code = a.product_code"
            + "  LEFT JOIN account_balance_agg b ON b.account_id = a.account_id"
            + " WHERE COALESCE(w.expected, 0) <> COALESCE(b.balance, 0)")) {
            ps.setString(1, LoanCatalog.P_WRITTEN_OFF);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, businessDate);
            ps.setObject(4, businessDate);
            ps.setObject(5, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK_WRITTEN_OFF, rs.getString(1), rs.getBigDecimal(2),
                        rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement du hors bilan des creances en perte", e);
        }
        return discrepancies;
    }

    public static List<Reconciliation.Discrepancy> accruedInterest(Connection c,
                                                                   UUID legalEntityId,
                                                                   LocalDate businessDate) {
        List<Reconciliation.Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "WITH gl AS (SELECT DISTINCT account_id FROM (" + PRODUCT_ACCOUNTS + ") p"
            + "             UNION SELECT DISTINCT a.accrued_account_id"
            + "               FROM loan_interest_accrual a JOIN loan_contract k ON k.id = a.contract_id"
            + "              WHERE k.legal_entity_id = ? AND a.status = 'ACTIVE'"
            + "                AND a.accrual_date >= ?),"
            + " open_lines AS ("
            + "   SELECT a.accrued_account_id, SUM(a.posted_delta) AS amount"
            + "     FROM loan_schedule_line l"
            + "     JOIN loan_schedule s ON s.id = l.schedule_id"
            + "     JOIN loan_contract k ON k.id = s.contract_id"
            + "     JOIN loan_interest_accrual a ON a.schedule_id = l.schedule_id"
            + "                                 AND a.instalment_number = l.number"
            + "                                 AND a.status = 'ACTIVE'"
            + "    WHERE k.legal_entity_id = ? AND l.made_due_on IS NULL AND l.period_start <= ?"
            + "    GROUP BY a.accrued_account_id)"
            + " SELECT gl.account_id, COALESCE(o.amount, 0), COALESCE(b.balance, 0)"
            + "   FROM gl"
            + "   LEFT JOIN open_lines o ON o.accrued_account_id = gl.account_id"
            + "   LEFT JOIN account_balance_agg b ON b.account_id = gl.account_id"
            + "  WHERE COALESCE(o.amount, 0) <> COALESCE(b.balance, 0)")) {
            ps.setString(1, LoanCatalog.P_ACCRUED_INTEREST);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, businessDate);
            ps.setObject(4, businessDate);
            ps.setObject(5, legalEntityId);
            // Les comptes de courus vus par les journees recentes : une echeance en cours a au
            // plus un an, les journees plus anciennes sont reprises ou reclamees.
            ps.setObject(6, businessDate.minusMonths(13));
            ps.setObject(7, legalEntityId);
            ps.setObject(8, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Reconciliation.Discrepancy(
                        CHECK_ACCRUED, rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Rapprochement des interets courus sur credits", e);
        }
        return discrepancies;
    }
}
