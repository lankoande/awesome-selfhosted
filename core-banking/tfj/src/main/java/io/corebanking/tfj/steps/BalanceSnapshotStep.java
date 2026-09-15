package io.corebanking.tfj.steps;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.tfj.Runs;
import io.corebanking.tfj.StepResult;
import io.corebanking.tfj.TfjContext;
import io.corebanking.tfj.TfjStep;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Arrete des soldes de la journee.
 *
 * <p>Deux soldes sont figes, et les confondre est l'erreur qui produit des agios faux :
 * le solde <b>en date comptable</b>, qui rattache l'operation a l'exercice, et le solde
 * <b>en date de valeur</b>, seule assiette licite du calcul des interets.
 *
 * <h2>Incremental</h2>
 *
 * <p>Le cliche du jour repart du cliche de la derniere journee arretee et n'y ajoute que ce qui
 * s'est passe depuis : les lignes comptabilisees depuis cette journee — par leur partition — et,
 * pour le solde en date de valeur, les lignes plus anciennes dont la date de valeur tombe dans
 * l'intervalle. Rejouer tout le journal chaque nuit coute O(historique) ; ceci coute la journee.
 * Un compte sans cliche precedent — ouvert depuis, ou premier arrete de l'entite — est rejoue
 * depuis l'origine, et lui seul.
 *
 * <p>Le cliche n'est pas un cache : il rend le rejeu instantane sur dix ans d'historique, et c'est
 * contre lui que le solde materialise est controle chaque nuit. Le rejeu integral, lui, est
 * l'affaire de l'arrete mensuel.
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
        long written = database.inTransaction(c -> snapshot(c, context));
        // ON CONFLICT DO UPDATE : rejouer l'etape recalcule le cliche au lieu d'echouer.
        return StepResult.of(written, written);
    }

    /**
     * Arrete les soldes de la journee, dans la transaction donnee. Idempotent : la reconciliation
     * le rappelle avant de controler, pour qu'une correction passee entre un echec et la reprise
     * entre dans le cliche qu'elle controle.
     */
    public static long snapshot(Connection c, TfjContext context) {
        Optional<LocalDate> previous = Runs.previousCompletedDay(c, context.legalEntityId(),
                                                                 context.businessDate());
        long incremental = previous.isPresent()
            ? snapshotFromPrevious(c, context, previous.get()) : 0;
        long replayed = snapshotByReplay(c, context, previous.orElse(null));
        return incremental + replayed;
    }

    /** Comptes qui ont un cliche a la journee precedente : cliche precedent plus la periode. */
    private static long snapshotFromPrevious(Connection c, TfjContext context, LocalDate previous) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_balance_daily(account_id, business_date, closing_balance,"
            + " value_date_balance, debit_turnover, credit_turnover)"
            + " SELECT a.id, ?::date,"
            + "        p.closing_balance + COALESCE(t.booked, 0),"
            + "        p.value_date_balance + COALESCE(t.valued, 0) + COALESCE(v.forward, 0),"
            + "        COALESCE(t.debit, 0), COALESCE(t.credit, 0)"
            + "   FROM account a"
            + "   JOIN account_balance_daily p ON p.account_id = a.id AND p.business_date = ?::date"
            + "   LEFT JOIN ("
            + "        SELECT l.account_id,"
            + "               SUM(CASE WHEN l.direction = x.normal_balance THEN l.amount"
            + "                        ELSE -l.amount END) AS booked,"
            + "               SUM(CASE WHEN l.value_date <= ?::date THEN"
            + "                        CASE WHEN l.direction = x.normal_balance THEN l.amount"
            + "                             ELSE -l.amount END ELSE 0 END) AS valued,"
            + "               SUM(CASE WHEN l.booking_date = ?::date AND l.direction = 'DEBIT'"
            + "                        THEN l.amount ELSE 0 END) AS debit,"
            + "               SUM(CASE WHEN l.booking_date = ?::date AND l.direction = 'CREDIT'"
            + "                        THEN l.amount ELSE 0 END) AS credit"
            + "          FROM journal_line l JOIN account x ON x.id = l.account_id"
            + "         WHERE x.legal_entity_id = ?"
            + "           AND l.booking_date > ?::date AND l.booking_date <= ?::date"
            + "         GROUP BY l.account_id) t ON t.account_id = a.id"
            + "   LEFT JOIN ("
            + "        SELECT l.account_id,"
            + "               SUM(CASE WHEN l.direction = x.normal_balance THEN l.amount"
            + "                        ELSE -l.amount END) AS forward"
            + "          FROM journal_line l JOIN account x ON x.id = l.account_id"
            + "         WHERE x.legal_entity_id = ?"
            + "           AND l.booking_date <= ?::date"
            + "           AND l.value_date > ?::date AND l.value_date <= ?::date"
            + "         GROUP BY l.account_id) v ON v.account_id = a.id"
            + "  WHERE a.legal_entity_id = ?"
            + " ON CONFLICT (account_id, business_date) DO UPDATE"
            + "    SET closing_balance = EXCLUDED.closing_balance,"
            + "        value_date_balance = EXCLUDED.value_date_balance,"
            + "        debit_turnover = EXCLUDED.debit_turnover,"
            + "        credit_turnover = EXCLUDED.credit_turnover")) {
            LocalDate today = context.businessDate();
            ps.setObject(1, today);
            ps.setObject(2, previous);
            ps.setObject(3, today);
            ps.setObject(4, today);
            ps.setObject(5, today);
            ps.setObject(6, context.legalEntityId());
            ps.setObject(7, previous);
            ps.setObject(8, today);
            ps.setObject(9, context.legalEntityId());
            ps.setObject(10, previous);
            ps.setObject(11, previous);
            ps.setObject(12, today);
            ps.setObject(13, context.legalEntityId());
            return (long) ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Arrete incremental des soldes du jour", e);
        }
    }

    /** Comptes sans cliche precedent : rejoues depuis l'origine. */
    private static long snapshotByReplay(Connection c, TfjContext context, LocalDate previous) {
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
            + "    AND (?::date IS NULL OR NOT EXISTS ("
            + "         SELECT 1 FROM account_balance_daily p"
            + "          WHERE p.account_id = a.id AND p.business_date = ?::date))"
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
            ps.setObject(7, previous);
            ps.setObject(8, previous);
            return (long) ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Arrete des soldes du jour par rejeu", e);
        }
    }
}
