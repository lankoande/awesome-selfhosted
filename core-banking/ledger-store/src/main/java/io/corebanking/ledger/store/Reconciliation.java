package io.corebanking.ledger.store;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Controles d'integrite du ledger.
 *
 * <p>Ils s'executent a chaque TFJ et <b>bloquent la bascule de journee</b> au moindre ecart. Le
 * seuil de tolerance est zero. La tentation d'en accepter un est forte en exploitation ; elle est
 * toujours perdante : un ecart admis aujourd'hui devient en fin d'exercice un ecart dont personne
 * ne retrouve l'origine.
 *
 * <h2>Quotidien et mensuel</h2>
 *
 * <p>Deux des controles rejouent le journal depuis l'origine. C'est le controle souverain, et il
 * coute O(historique) : quelques millisecondes sur une journee de tests, quelques heures apres
 * deux ans a deux millions de comptes. Il n'est donc execute qu'a l'arrete mensuel. Chaque
 * journee, le controle porte sur la <b>journee</b> : ses lignes s'equilibrent, et le solde
 * materialise egale le cliche du jour, lui-meme construit depuis le cliche de la veille et les
 * lignes de la journee. Par recurrence depuis le dernier rejeu integral verifie, c'est la meme
 * garantie — au cout de la journee et non de l'historique.
 *
 * <h2>Sous-livres</h2>
 *
 * <p>Le grand livre equilibre ne dit rien des sous-livres : une creance de credit sans ecriture,
 * une commission comptabilisee deux fois, des courus imputes sur le mauvais compte laissent la
 * balance equilibree. Chaque module apporte donc ses propres rapprochements par {@link Check},
 * et l'etape de reconciliation les execute tous.
 */
public final class Reconciliation {

    private Reconciliation() {}

    /** Ecart constate par un controle. Une liste vide est le seul resultat acceptable. */
    public record Discrepancy(String check, String scope, BigDecimal expected, BigDecimal actual) {
        public BigDecimal gap() {
            return actual.subtract(expected);
        }

        @Override
        public String toString() {
            return check + " [" + scope + "] attendu " + expected.toPlainString()
                   + ", constate " + actual.toPlainString();
        }
    }

    /**
     * Rapprochement apporte par un module : son sous-livre contre le grand livre.
     *
     * <p>Un controle est ecrit pour etre execute chaque nuit : il ne parcourt que les positions
     * ouvertes — creances non soldees, courus non regles, echeances en cours — jamais
     * l'historique. Ce que le sous-livre dit devoir se trouver au grand livre doit s'y trouver
     * exactement.
     */
    @FunctionalInterface
    public interface Check {

        /**
         * @param businessDate journee arretee
         * @param runId        traitement en cours, pour les controles qui rapprochent ce qu'un
         *                     traitement a comptabilise de ce qu'il a enregistre
         */
        List<Discrepancy> run(Connection connection, UUID legalEntityId, LocalDate businessDate,
                              UUID runId);
    }

    // ------------------------------------------------------------------ equilibre

    /**
     * Controle 1 — la balance generale est equilibree, par entite et par devise, depuis
     * l'origine.
     *
     * <p>C'est le controle souverain : s'il passe, aucune ecriture desequilibree n'a ete
     * enregistree depuis l'origine. Il rejoue tout le journal : reserve a l'arrete mensuel.
     */
    public static List<Discrepancy> generalLedgerBalanced(Connection c, UUID entityId) {
        return generalLedgerBalanced(c, entityId, null, null);
    }

    /**
     * Equilibre des lignes comptabilisees sur une plage de dates, bornes incluses. Une borne
     * nulle est absente.
     */
    public static List<Discrepancy> generalLedgerBalanced(Connection c, UUID entityId,
                                                         LocalDate from, LocalDate to) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT currency,"
            + "       SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS gap"
            + "  FROM journal_line WHERE legal_entity_id = ?"
            + "   AND (?::date IS NULL OR booking_date >= ?::date)"
            + "   AND (?::date IS NULL OR booking_date <= ?::date)"
            + " GROUP BY currency"
            + " HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0")) {
            ps.setObject(1, entityId);
            ps.setObject(2, from);
            ps.setObject(3, from);
            ps.setObject(4, to);
            ps.setObject(5, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "BALANCE_EQUILIBREE", rs.getString(1), BigDecimal.ZERO, rs.getBigDecimal(2)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle d'equilibre de la balance", e);
        }
        return discrepancies;
    }

    // ------------------------------------------------------------------ soldes

    /**
     * Controle 2 — le solde materialise egale le solde rejoue depuis le journal.
     *
     * <p>Il valide que le cache de soldes n'a pas derive. Rejeu integral : arrete mensuel.
     */
    public static List<Discrepancy> materializedMatchesReplay(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "WITH replayed AS ("
            + "   SELECT l.account_id,"
            + "          SUM(CASE WHEN l.direction = a.normal_balance THEN l.amount"
            + "                   ELSE -l.amount END) AS balance"
            + "     FROM journal_line l JOIN account a ON a.id = l.account_id"
            + "    WHERE l.legal_entity_id = ?"
            + "    GROUP BY l.account_id),"
            + " materialized AS ("
            + "   SELECT b.account_id, SUM(b.balance) AS balance"
            + "     FROM account_balance b JOIN account a ON a.id = b.account_id"
            + "    WHERE a.legal_entity_id = ?"
            + "    GROUP BY b.account_id)"
            + " SELECT m.account_id, COALESCE(r.balance, 0), m.balance"
            + "   FROM materialized m LEFT JOIN replayed r ON r.account_id = m.account_id"
            + "  WHERE m.balance <> COALESCE(r.balance, 0)")) {
            ps.setObject(1, entityId);
            ps.setObject(2, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "SOLDE_MATERIALISE_VS_REJOUE", rs.getString(1),
                        rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des soldes materialises", e);
        }
        return discrepancies;
    }

    /**
     * Controle 2 quotidien — le solde materialise egale le cliche de la journee.
     *
     * <p>Le cliche est construit depuis le cliche de la veille et les lignes de la journee ; le
     * solde materialise est tenu ligne a ligne par le service de comptabilisation. Ce sont deux
     * chemins independants vers le meme nombre : un solde mis a jour sur la mauvaise stripe, une
     * ligne perdue, un cliche recopie de travers les separent. Tout compte de l'entite doit avoir
     * son cliche : un compte sans cliche est un ecart, pas une absence.
     */
    public static List<Discrepancy> materializedMatchesSnapshot(Connection c, UUID entityId,
                                                               LocalDate businessDate) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        // Le cliche arrete les lignes comptabilisees jusqu'a la journee incluse ; le solde
        // materialise porte toutes les lignes. Une ligne datee apres la journee — rare, mais
        // licite tant que sa periode est ouverte — separe les deux sans que rien ne soit faux :
        // elle est ajoutee au cliche avant comparaison, par sa partition et son index.
        try (PreparedStatement ps = c.prepareStatement(
            "WITH later AS ("
            + "   SELECT l.account_id,"
            + "          SUM(CASE WHEN l.direction = x.normal_balance THEN l.amount"
            + "                   ELSE -l.amount END) AS amount"
            + "     FROM journal_line l JOIN account x ON x.id = l.account_id"
            + "    WHERE x.legal_entity_id = ? AND l.booking_date > ?"
            + "    GROUP BY l.account_id),"
            + " materialized AS ("
            + "   SELECT account_id, SUM(balance) AS balance FROM account_balance GROUP BY account_id)"
            + " SELECT a.id, s.closing_balance, COALESCE(later.amount, 0), COALESCE(m.balance, 0)"
            + "   FROM account a"
            + "   LEFT JOIN account_balance_daily s ON s.account_id = a.id AND s.business_date = ?"
            + "   LEFT JOIN later ON later.account_id = a.id"
            + "   LEFT JOIN materialized m ON m.account_id = a.id"
            + "  WHERE a.legal_entity_id = ?"
            + "    AND (s.closing_balance IS NULL"
            + "         OR s.closing_balance + COALESCE(later.amount, 0) <> COALESCE(m.balance, 0))")) {
            ps.setObject(1, entityId);
            ps.setObject(2, businessDate);
            ps.setObject(3, businessDate);
            ps.setObject(4, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal snapshot = rs.getBigDecimal(2);
                    BigDecimal expected = snapshot == null ? BigDecimal.ZERO
                                                           : snapshot.add(rs.getBigDecimal(3));
                    discrepancies.add(new Discrepancy(
                        "SOLDE_MATERIALISE_VS_CLICHE", rs.getString(1), expected,
                        rs.getBigDecimal(4)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des soldes contre le cliche du jour", e);
        }
        return discrepancies;
    }

    /**
     * Controle 3 — la somme des stripes egale le solde agrege du compte.
     *
     * <p>Il detecte une repartition de solde corrompue sur les comptes chauds : une mise a jour
     * appliquee a une stripe inexistante, ou un compte dont le nombre de stripes a change sans
     * reprise des soldes.
     */
    public static List<Discrepancy> stripesConsistent(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.id, a.stripe_count, count(b.stripe_id)"
            + "  FROM account a LEFT JOIN account_balance b ON b.account_id = a.id"
            + " WHERE a.legal_entity_id = ?"
            + " GROUP BY a.id, a.stripe_count"
            + " HAVING count(b.stripe_id) <> a.stripe_count")) {
            ps.setObject(1, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy(
                        "STRIPES_COMPLETES", rs.getString(1),
                        BigDecimal.valueOf(rs.getInt(2)), BigDecimal.valueOf(rs.getInt(3))));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle des stripes", e);
        }
        return discrepancies;
    }

    // ------------------------------------------------------------------ ensembles

    /**
     * Controles de la journee, bloquants pour la bascule : equilibre des lignes comptabilisees
     * depuis le cliche precedent, soldes materialises contre le cliche du jour, stripes.
     *
     * @param previousSnapshot date du cliche precedent, nulle s'il n'y en a aucun — les
     *                         controles portent alors sur tout l'historique
     */
    public static List<Discrepancy> dailyChecks(Connection c, UUID entityId,
                                                LocalDate businessDate,
                                                LocalDate previousSnapshot) {
        List<Discrepancy> all = new ArrayList<>();
        all.addAll(generalLedgerBalanced(c, entityId,
                                         previousSnapshot == null ? null
                                                                  : previousSnapshot.plusDays(1),
                                         businessDate));
        all.addAll(materializedMatchesSnapshot(c, entityId, businessDate));
        all.addAll(stripesConsistent(c, entityId));
        all.addAll(interbranchMirror(c, entityId, businessDate));
        return all;
    }

    /**
     * Controle 4 — compensation inter-agences, sur le cliche par agence de la journee.
     *
     * <ul>
     *   <li><b>Le miroir</b> : pour chaque compte de liaison, son solde dans les livres de son
     *       agence et son solde dans les livres du siege s'annulent.</li>
     *   <li><b>L'elimination</b> : la somme d'un compte de liaison sur toutes les agences est
     *       nulle — a la consolidation, il ne reste rien.</li>
     *   <li><b>Le cliche par agence somme au cliche par compte</b> : la balance agence n'est pas
     *       une autre verite que la balance de l'entite.</li>
     * </ul>
     *
     * <p>Un ecart nomme l'agence et le compte, et bloque la journee : dans une meme entite, les
     * comptes de liaison ne se reglent pas, ils s'eliminent, et un residu est une ecriture qui
     * manque quelque part.
     */
    public static List<Discrepancy> interbranchMirror(Connection c, UUID entityId,
                                                      LocalDate businessDate) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            LIAISON_CTE
            + " bal AS (SELECT account_id, branch_id, closing_balance FROM branch_balance_daily"
            + "          WHERE business_date = ?)"
            + MIRROR_SELECT)) {
            ps.setObject(1, entityId);
            ps.setObject(2, businessDate);
            collectMirror(ps, discrepancies);
        } catch (SQLException e) {
            throw new LedgerStoreException("Compensation inter-agences sur le cliche", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT a.code, s.closing_balance, COALESCE(SUM(bb.closing_balance), 0)"
            + "  FROM account a"
            + "  JOIN account_balance_daily s ON s.account_id = a.id AND s.business_date = ?"
            + "  LEFT JOIN branch_balance_daily bb ON bb.account_id = a.id AND bb.business_date = ?"
            + " WHERE a.legal_entity_id = ?"
            + " GROUP BY a.code, s.closing_balance"
            + " HAVING s.closing_balance <> COALESCE(SUM(bb.closing_balance), 0)")) {
            ps.setObject(1, businessDate);
            ps.setObject(2, businessDate);
            ps.setObject(3, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    discrepancies.add(new Discrepancy("CLICHE_AGENCES_VS_COMPTE", rs.getString(1),
                                                      rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Cliche par agence contre cliche par compte", e);
        }
        return discrepancies;
    }

    /** Le meme controle, rejoue integralement sur le journal : reserve a l'arrete mensuel. */
    public static List<Discrepancy> interbranchMirrorReplayed(Connection c, UUID entityId) {
        List<Discrepancy> discrepancies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            LIAISON_CTE
            + " bal AS (SELECT l.account_id, l.branch_id,"
            + "                SUM(CASE WHEN l.direction = x.normal_balance THEN l.amount"
            + "                         ELSE -l.amount END) AS closing_balance"
            + "           FROM journal_line l JOIN account x ON x.id = l.account_id"
            + "          WHERE l.legal_entity_id = ?"
            + "            AND l.account_id IN (SELECT account_id FROM liaison)"
            + "          GROUP BY l.account_id, l.branch_id)"
            + MIRROR_SELECT)) {
            ps.setObject(1, entityId);
            ps.setObject(2, entityId);
            collectMirror(ps, discrepancies);
        } catch (SQLException e) {
            throw new LedgerStoreException("Compensation inter-agences par rejeu", e);
        }
        return discrepancies;
    }

    private static final String LIAISON_CTE =
        "WITH liaison AS ("
        + "   SELECT l.branch_id, l.account_id, b.code AS branch_code, a.code AS account_code,"
        + "          h.id AS head_office"
        + "     FROM branch_liaison l"
        + "     JOIN branch b ON b.id = l.branch_id"
        + "     JOIN account a ON a.id = l.account_id"
        + "     JOIN branch h ON h.legal_entity_id = b.legal_entity_id AND h.kind = 'HEAD_OFFICE'"
        + "    WHERE b.legal_entity_id = ?),";

    private static final String MIRROR_SELECT =
        " SELECT li.branch_code, li.account_code,"
        + "       COALESCE((SELECT closing_balance FROM bal"
        + "                  WHERE bal.account_id = li.account_id AND bal.branch_id = li.branch_id), 0),"
        + "       COALESCE((SELECT closing_balance FROM bal"
        + "                  WHERE bal.account_id = li.account_id AND bal.branch_id = li.head_office), 0),"
        + "       COALESCE((SELECT SUM(closing_balance) FROM bal"
        + "                  WHERE bal.account_id = li.account_id), 0)"
        + "   FROM liaison li ORDER BY li.branch_code";

    private static void collectMirror(PreparedStatement ps, List<Discrepancy> discrepancies)
            throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String scope = "agence " + rs.getString(1) + " / " + rs.getString(2);
                BigDecimal mirror = rs.getBigDecimal(3).add(rs.getBigDecimal(4));
                if (mirror.signum() != 0) {
                    discrepancies.add(new Discrepancy("LIAISON_AGENCE_MIROIR", scope,
                                                      BigDecimal.ZERO, mirror));
                }
                BigDecimal total = rs.getBigDecimal(5);
                if (total.signum() != 0) {
                    discrepancies.add(new Discrepancy("LIAISON_ELIMINATION", scope,
                                                      BigDecimal.ZERO, total));
                }
            }
        }
    }

    /** Ensemble des controles integraux, par rejeu du journal depuis l'origine : arrete mensuel. */
    public static List<Discrepancy> allBlockingChecks(Connection c, UUID entityId) {
        List<Discrepancy> all = new ArrayList<>();
        all.addAll(generalLedgerBalanced(c, entityId));
        all.addAll(materializedMatchesReplay(c, entityId));
        all.addAll(stripesConsistent(c, entityId));
        all.addAll(interbranchMirrorReplayed(c, entityId));
        return all;
    }
}
