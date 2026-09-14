package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Arrete des soldes de la journee.
 *
 * <p>Deux soldes sont figes, et les confondre est l'erreur qui produit des agios faux :
 * le solde <b>en date comptable</b>, qui rattache l'operation a l'exercice, et le solde
 * <b>en date de valeur</b>, seule assiette licite du calcul des interets.
 *
 * <p>Le cliche n'est pas un cache : il rend le rejeu instantane sur dix ans d'historique, alors
 * qu'une reconstruction integrale depuis l'origine deviendrait impraticable a mesure que le
 * journal grossit.
 */
public final class BalanceSnapshotStep implements TfjStep {

    private final Database database;

    public BalanceSnapshotStep(Database database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "BALANCE_SNAPSHOT";
    }

    @Override
    public boolean blocking() {
        return true;
    }

    @Override
    public StepResult execute(TfjContext context) {
        long written = database.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO account_balance_daily(account_id, business_date, closing_balance,"
                + " value_date_balance, debit_turnover, credit_turnover)"
                + " SELECT a.id, ?::date,"
                + "        COALESCE(SUM(CASE WHEN l.booking_date <= ?::date"
                + "                          THEN CASE WHEN l.direction = a.normal_balance"
                + "                                    THEN l.amount ELSE -l.amount END"
                + "                          ELSE 0 END), 0),"
                + "        COALESCE(SUM(CASE WHEN l.value_date <= ?::date"
                + "                          THEN CASE WHEN l.direction = a.normal_balance"
                + "                                    THEN l.amount ELSE -l.amount END"
                + "                          ELSE 0 END), 0),"
                + "        COALESCE(SUM(CASE WHEN l.booking_date = ?::date AND l.direction = 'DEBIT'"
                + "                          THEN l.amount ELSE 0 END), 0),"
                + "        COALESCE(SUM(CASE WHEN l.booking_date = ?::date AND l.direction = 'CREDIT'"
                + "                          THEN l.amount ELSE 0 END), 0)"
                + "   FROM account a LEFT JOIN journal_line l ON l.account_id = a.id"
                + "  WHERE a.legal_entity_id = ?"
                + "  GROUP BY a.id"
                + " ON CONFLICT (account_id, business_date) DO UPDATE"
                + "    SET closing_balance = EXCLUDED.closing_balance,"
                + "        value_date_balance = EXCLUDED.value_date_balance,"
                + "        debit_turnover = EXCLUDED.debit_turnover,"
                + "        credit_turnover = EXCLUDED.credit_turnover")) {
                for (int i = 1; i <= 5; i++) {
                    ps.setObject(i, context.businessDate());
                }
                ps.setObject(6, context.legalEntityId());
                return (long) ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Arrete des soldes du jour", e);
            }
        });
        // ON CONFLICT DO UPDATE : rejouer l'etape recalcule le cliche au lieu d'echouer.
        return StepResult.of(written, written);
    }
}
