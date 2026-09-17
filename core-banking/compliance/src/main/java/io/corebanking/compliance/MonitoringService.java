package io.corebanking.compliance;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Surveillance des operations : les scenarios en vigueur, passes sur la journee arretee.
 *
 * <h2>Ce que la surveillance regarde, et ce qu'elle ne regarde pas</h2>
 *
 * <p>Elle regarde <b>ce qui s'est passe aujourd'hui</b>, et le replace dans sa fenetre. Un
 * scenario qui relirait chaque nuit trente jours entiers sans egard pour la journee reclamerait
 * les memes faits trente fois ; celui qui ne regarderait que la journee ne verrait jamais un
 * fractionnement, qui est par definition une suite. La regle tenue ici : <b>une alerte n'est levee
 * que si la journee a contribue</b>, et pas deux fois sur la meme fenetre.
 *
 * <p>Elle ne regarde que les <b>comptes de clients</b>, et remonte au tiers qui en repond : c'est
 * la personne qui est surveillee, pas le compte. Un client qui repartit ses depots sur trois
 * comptes ne doit pas devenir trois clients discrets.
 *
 * <p>Elle ne bloque rien et n'arrete pas l'arrete : un client suspect n'est pas une panne de la
 * banque. Ce qui arrete la journee, c'est un scenario qui ne s'execute pas — un defaut de
 * parametrage, pas un fait de client.
 */
public final class MonitoringService {

    /** Les operations que les scenarios d'especes regardent. */
    private static final String CASH_TYPES = "('CASH_DEPOSIT','CASH_WITHDRAWAL')";

    private final Database database;

    public MonitoringService(Database database) {
        this.database = database;
    }

    /** Compte rendu d'une passe de surveillance. */
    public record Result(int scenarios, int alerts) {}

    /**
     * Passe de surveillance sur une entite.
     *
     * <p>La portee d'entite est posee ici, et non laissee a l'appelant : la surveillance lit le
     * journal sous cloisonnement par entite, et une passe qui tournerait hors portee ne verrait
     * aucune operation — elle ne leverait donc aucune alerte, silencieusement. Sous l'arrete, la
     * portee est deja celle-ci, et la poser de nouveau ne change rien.
     */
    public Result run(UUID legalEntityId, LocalDate businessDate, UUID runId, UUID actorId) {
        try (Database.EntityScope scope = Database.enterEntity(legalEntityId)) {
            List<MonitoringScenarios.Scenario> scenarios = database.inTransaction(
                c -> MonitoringScenarios.activeOn(c, legalEntityId, businessDate));
            if (scenarios.isEmpty()) {
                return new Result(0, 0);
            }
            int raised = 0;
            for (MonitoringScenarios.Scenario scenario : scenarios) {
                raised += database.inTransaction(
                    c -> apply(c, scenario, legalEntityId, businessDate, runId));
            }
            return new Result(scenarios.size(), raised);
        }
    }

    private int apply(Connection c, MonitoringScenarios.Scenario scenario, UUID legalEntityId,
                      LocalDate on, UUID runId) {
        return switch (scenario.method()) {
            case CASH_THRESHOLD -> cashThreshold(c, scenario, legalEntityId, on, runId);
            case STRUCTURING -> structuring(c, scenario, legalEntityId, on, runId);
            case ATYPICAL_ACTIVITY -> atypicalActivity(c, scenario, legalEntityId, on, runId);
            case DORMANT_REACTIVATION -> dormantReactivation(c, scenario, legalEntityId, on, runId);
        };
    }

    // ------------------------------------------------------------------ especes cumulees

    /**
     * Especes cumulees au-dela du seuil sur la fenetre.
     *
     * <p>Le cumul porte sur le tiers, toutes ses operations d'especes confondues, dans les deux
     * sens : un client qui verse et retire alternativement fait circuler de l'argent, et c'est
     * exactement ce que le scenario cherche.
     */
    private int cashThreshold(Connection c, MonitoringScenarios.Scenario scenario, UUID entity,
                              LocalDate on, UUID runId) {
        LocalDate from = on.minusDays(scenario.windowDays() - 1L);
        String sql = """
            SELECT h.party_id, SUM(l.functional_amount) AS total
              FROM journal_line l
              JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date
              JOIN account a ON a.id = l.account_id
              JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= l.booking_date
                   AND (h.valid_to IS NULL OR h.valid_to >= l.booking_date)
              JOIN party p ON p.id = h.party_id
             WHERE e.legal_entity_id = ? AND a.account_kind = 'CUSTOMER'
               AND e.transaction_type IN """ + CASH_TYPES + """
               AND e.reversal_of IS NULL
               AND l.booking_date BETWEEN ? AND ?
               AND (?::text IS NULL OR p.risk_rating = ?)
             GROUP BY h.party_id
            HAVING SUM(l.functional_amount) > ?
               AND SUM(l.functional_amount) FILTER (WHERE l.booking_date = ?) > 0
            """;
        return raiseForEach(c, sql, scenario, entity, on, from, runId, ps -> {
            ps.setObject(1, entity);
            ps.setObject(2, from);
            ps.setObject(3, on);
            ps.setString(4, scenario.riskRating());
            ps.setString(5, scenario.riskRating());
            ps.setBigDecimal(6, scenario.thresholdAmount());
            ps.setObject(7, on);
        }, total -> "Especes cumulees de " + total + " sur " + scenario.windowDays()
                    + " jours, au-dela du seuil de " + scenario.thresholdAmount());
    }

    // ------------------------------------------------------------------ fractionnement

    /**
     * Fractionnement : des operations <b>chacune sous le seuil</b>, assez nombreuses, dont la
     * somme le franchit.
     *
     * <p>C'est la typologie la plus ancienne et la plus vivace : celui qui sait le seuil passe
     * juste en dessous, plusieurs fois. Le scenario ne se lit donc pas sur une operation — aucune
     * n'est anormale — mais sur leur suite.
     */
    private int structuring(Connection c, MonitoringScenarios.Scenario scenario, UUID entity,
                            LocalDate on, UUID runId) {
        LocalDate from = on.minusDays(scenario.windowDays() - 1L);
        String sql = """
            SELECT h.party_id, SUM(l.functional_amount) AS total
              FROM journal_line l
              JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date
              JOIN account a ON a.id = l.account_id
              JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= l.booking_date
                   AND (h.valid_to IS NULL OR h.valid_to >= l.booking_date)
              JOIN party p ON p.id = h.party_id
             WHERE e.legal_entity_id = ? AND a.account_kind = 'CUSTOMER'
               AND e.transaction_type IN """ + CASH_TYPES + """
               AND e.reversal_of IS NULL
               AND l.booking_date BETWEEN ? AND ?
               AND l.functional_amount < ?
               AND (?::text IS NULL OR p.risk_rating = ?)
             GROUP BY h.party_id
            HAVING COUNT(*) >= ?
               AND SUM(l.functional_amount) > ?
               AND COUNT(*) FILTER (WHERE l.booking_date = ?) > 0
            """;
        return raiseForEach(c, sql, scenario, entity, on, from, runId, ps -> {
            ps.setObject(1, entity);
            ps.setObject(2, from);
            ps.setObject(3, on);
            ps.setBigDecimal(4, scenario.thresholdAmount());
            ps.setString(5, scenario.riskRating());
            ps.setString(6, scenario.riskRating());
            ps.setInt(7, scenario.minimumCount());
            ps.setBigDecimal(8, scenario.thresholdAmount());
            ps.setObject(9, on);
        }, total -> "Operations d'especes toutes inferieures a " + scenario.thresholdAmount()
                    + ", au moins " + scenario.minimumCount() + " sur " + scenario.windowDays()
                    + " jours, totalisant " + total);
    }

    // ------------------------------------------------------------------ atypie

    /**
     * Flux hors de proportion avec le profil declare.
     *
     * <p>Le profil declare est ce qui rend cette question honnete : sans lui, on comparerait un
     * client a un autre, et les gros comptes seraient perpetuellement suspects d'etre gros. Le
     * seuil est le flux mensuel annonce, ramene a la fenetre, multiplie par le facteur toleré.
     */
    private int atypicalActivity(Connection c, MonitoringScenarios.Scenario scenario, UUID entity,
                                 LocalDate on, UUID runId) {
        LocalDate from = on.minusDays(scenario.windowDays() - 1L);
        String sql = """
            SELECT h.party_id, SUM(l.functional_amount) AS total
              FROM journal_line l
              JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date
              JOIN account a ON a.id = l.account_id
              JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= l.booking_date
                   AND (h.valid_to IS NULL OR h.valid_to >= l.booking_date)
              JOIN party p ON p.id = h.party_id
              JOIN party_activity_profile f ON f.party_id = h.party_id
             WHERE e.legal_entity_id = ? AND a.account_kind = 'CUSTOMER'
               AND l.direction = 'CREDIT' AND e.reversal_of IS NULL
               AND l.booking_date BETWEEN ? AND ?
               AND (?::text IS NULL OR p.risk_rating = ?)
             GROUP BY h.party_id, f.expected_monthly_credit
            HAVING SUM(l.functional_amount)
                   > f.expected_monthly_credit * ? * (?::numeric / 30)
               AND SUM(l.functional_amount) FILTER (WHERE l.booking_date = ?) > 0
            """;
        return raiseForEach(c, sql, scenario, entity, on, from, runId, ps -> {
            ps.setObject(1, entity);
            ps.setObject(2, from);
            ps.setObject(3, on);
            ps.setString(4, scenario.riskRating());
            ps.setString(5, scenario.riskRating());
            ps.setBigDecimal(6, scenario.ratio());
            ps.setInt(7, scenario.windowDays());
            ps.setObject(8, on);
        }, total -> "Flux crediteurs de " + total + " sur " + scenario.windowDays()
                    + " jours, au-dela de " + scenario.ratio() + " fois le profil declare");
    }

    // ------------------------------------------------------------------ reveil d'un dormant

    /**
     * Un compte oublie qui se remet a bouger, pour un montant qui compte.
     *
     * <p>La typologie est connue : un compte sans mouvement depuis des annees sert de reception a
     * des fonds dont personne ne surveille plus le titulaire. Le reveil est deja constate par le
     * cycle de vie du compte ; ici on le confronte au montant du jour.
     */
    private int dormantReactivation(Connection c, MonitoringScenarios.Scenario scenario,
                                    UUID entity, LocalDate on, UUID runId) {
        String sql = """
            SELECT h.party_id, SUM(l.functional_amount) AS total
              FROM account_event v
              JOIN account a ON a.id = v.account_id
              JOIN journal_line l ON l.account_id = a.id AND l.booking_date = v.occurred_on
              JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date
              JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= l.booking_date
                   AND (h.valid_to IS NULL OR h.valid_to >= l.booking_date)
              JOIN party p ON p.id = h.party_id
             WHERE v.kind = 'REACTIVATED' AND v.occurred_on = ?
               AND a.legal_entity_id = ? AND a.account_kind = 'CUSTOMER'
               AND e.reversal_of IS NULL
               AND (?::text IS NULL OR p.risk_rating = ?)
             GROUP BY h.party_id
            HAVING SUM(l.functional_amount) > ?
            """;
        return raiseForEach(c, sql, scenario, entity, on, on, runId, ps -> {
            ps.setObject(1, on);
            ps.setObject(2, entity);
            ps.setString(3, scenario.riskRating());
            ps.setString(4, scenario.riskRating());
            ps.setBigDecimal(5, scenario.thresholdAmount());
        }, total -> "Compte dormant reactive, mouvements du jour de " + total
                    + ", au-dela du seuil de " + scenario.thresholdAmount());
    }

    // ------------------------------------------------------------------ outillage commun

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Narrative {
        String of(BigDecimal total);
    }

    /**
     * Execute la requete d'un scenario et leve une alerte par tiers retenu, pieces comprises.
     *
     * <p>Une alerte deja levee sur la meme fenetre arrete la : c'est ce qui evite a la conformite
     * de recevoir trente fois le meme fait, et de cesser de lire sa file.
     */
    private int raiseForEach(Connection c, String sql, MonitoringScenarios.Scenario scenario,
                             UUID entity, LocalDate on, LocalDate from, UUID runId, Binder binder,
                             Narrative narrative) {
        Map<UUID, BigDecimal> retained = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    retained.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Scenario " + scenario.code(), e);
        }
        int raised = 0;
        for (Map.Entry<UUID, BigDecimal> entry : retained.entrySet()) {
            if (AmlAlerts.alreadyRaised(c, entry.getKey(), scenario.code(), from, on)) {
                continue;
            }
            Money total = Money.of(entry.getValue(), functionalCurrency(c, entity))
                .roundToCurrency();
            AmlAlerts.raise(c, entity, entry.getKey(), scenario.code(),
                            AmlAlerts.Origin.MONITORING, on,
                            scenario.label() + " — " + narrative.of(total.amount()), total,
                            evidence(c, entry.getKey(), from, on, entity), runId);
            raised++;
        }
        return raised;
    }

    /**
     * Les operations du tiers sur la fenetre : les pieces du dossier.
     *
     * <p>Elles sont bornees a ce qu'un analyste peut lire ; au-dela, ce n'est plus une piece, c'est
     * un export.
     */
    private static final int MAX_ITEMS = 50;

    private List<AmlAlerts.Item> evidence(Connection c, UUID partyId, LocalDate from, LocalDate to,
                                          UUID entity) {
        List<AmlAlerts.Item> items = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT l.entry_id, l.booking_date, l.account_id, l.direction, l.amount, l.currency,
                   cur.scale, cur.rounding_mode
              FROM journal_line l
              JOIN journal_entry e ON e.id = l.entry_id AND e.booking_date = l.booking_date
              JOIN account a ON a.id = l.account_id
              JOIN account_holder h ON h.account_id = a.id AND h.role = 'HOLDER'
                   AND h.valid_from <= l.booking_date
                   AND (h.valid_to IS NULL OR h.valid_to >= l.booking_date)
              JOIN currency cur ON cur.code = l.currency
             WHERE h.party_id = ? AND a.account_kind = 'CUSTOMER' AND e.legal_entity_id = ?
               AND e.reversal_of IS NULL AND l.booking_date BETWEEN ? AND ?
             ORDER BY l.booking_date DESC, l.entry_id
             LIMIT """ + " " + MAX_ITEMS)) {
            ps.setObject(1, partyId);
            ps.setObject(2, entity);
            ps.setObject(3, from);
            ps.setObject(4, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(6), rs.getInt(7),
                        RoundingMode.valueOf(rs.getString(8)));
                    items.add(new AmlAlerts.Item(rs.getObject(1, UUID.class),
                        rs.getObject(2, LocalDate.class), rs.getObject(3, UUID.class),
                        rs.getString(4),
                        Money.of(rs.getBigDecimal(5), currency).roundToCurrency()));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Pieces de l'alerte", e);
        }
        return items;
    }

    private CurrencyRef functionalCurrency(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cur.code, cur.scale, cur.rounding_mode FROM legal_entity e"
            + "  JOIN currency cur ON cur.code = e.functional_currency WHERE e.id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return new CurrencyRef(rs.getString(1), rs.getInt(2),
                                       RoundingMode.valueOf(rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Devise de tenue de l'entite", e);
        }
    }
}
