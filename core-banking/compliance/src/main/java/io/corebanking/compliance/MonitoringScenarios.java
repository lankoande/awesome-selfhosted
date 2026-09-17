package io.corebanking.compliance;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Scenarios de surveillance : la methode est du code, les seuils sont du parametrage.
 *
 * <p>C'est la seule facon de tenir un dispositif LCB-FT vivant. Les seuils, les fenetres et les
 * populations changent avec la reglementation et avec les typologies locales — parfois d'une
 * circulaire a l'autre. Les coder condamnerait la banque a une livraison a chaque changement, et
 * une conformite qui attend la prochaine version n'est pas une conformite.
 *
 * <p>Ce qui reste du code, c'est <b>la facon de compter</b> : cumuler des especes sur une fenetre,
 * reconnaitre un fractionnement, confronter des flux a un profil declare, voir un compte oublie se
 * reveiller. Ajouter une methode est une livraison ; c'est normal, elle change ce que la banque
 * sait regarder.
 */
public final class MonitoringScenarios {

    private MonitoringScenarios() {}

    /** Dix ans : au-dela, une fenetre de surveillance ne surveille plus, elle archive. */
    public static final int MAX_WINDOW_DAYS = 3650;

    /** Ce que le code sait compter. */
    public enum Method {
        /** Especes cumulees au-dela d'un montant, sur une fenetre. */
        CASH_THRESHOLD,
        /** Operations sous le seuil, repetees, dont la somme le franchit : le fractionnement. */
        STRUCTURING,
        /** Flux hors de proportion avec le profil declare au dossier. */
        ATYPICAL_ACTIVITY,
        /** Un compte oublie qui se remet a bouger. */
        DORMANT_REACTIVATION
    }

    public record Scenario(UUID id, UUID legalEntityId, String code, String label, Method method,
                           BigDecimal thresholdAmount, Integer windowDays, Integer minimumCount,
                           BigDecimal ratio, String riskRating, LocalDate validFrom,
                           LocalDate validTo) {}

    public record Draft(UUID legalEntityId, String code, String label, Method method,
                        BigDecimal thresholdAmount, Integer windowDays, Integer minimumCount,
                        BigDecimal ratio, String riskRating, LocalDate validFrom,
                        LocalDate validTo, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(validFrom, "validFrom");
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Un scenario porte son libelle : c'est ce que "
                    + "l'analyste lira sur l'alerte");
            }
            requireParameters(method, thresholdAmount, windowDays, minimumCount, ratio);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Un scenario ne cesse pas avant de commencer : "
                    + validFrom + " a " + validTo);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Un scenario de surveillance se declare a "
                    + "deux : il decide de ce que la banque regarde, et de ce qu'elle ne regarde "
                    + "pas");
            }
        }
    }

    /**
     * Ce que chaque methode exige pour vouloir dire quelque chose.
     *
     * <p>La base le redit par contrainte ; le dire ici nomme le manque avant l'insertion, et
     * surtout <b>a la soumission</b> plutot qu'a l'approbation : un valideur ne doit pas decouvrir
     * un scenario qui ne surveille rien.
     */
    public static void requireParameters(Method method, BigDecimal thresholdAmount,
                                         Integer windowDays, Integer minimumCount,
                                         BigDecimal ratio) {
        Objects.requireNonNull(method, "method");
        // Une fenetre se compte en jours de surveillance, pas en siecles : au-dela de dix ans
        // elle ne veut plus rien dire, et le calcul de la date de depart sortirait du calendrier.
        if (windowDays != null && (windowDays < 1 || windowDays > MAX_WINDOW_DAYS)) {
            throw new IllegalArgumentException("Une fenetre de surveillance va de 1 a "
                + MAX_WINDOW_DAYS + " jours : " + windowDays);
        }
        if (ratio != null && ratio.signum() <= 0) {
            throw new IllegalArgumentException("Un facteur d'ecart est positif : " + ratio);
        }
        if (thresholdAmount != null && thresholdAmount.signum() <= 0) {
            throw new IllegalArgumentException("Un seuil est positif : " + thresholdAmount);
        }
        switch (method) {
            case CASH_THRESHOLD -> requireAll(method, thresholdAmount, windowDays);
            case STRUCTURING -> {
                requireAll(method, thresholdAmount, windowDays, minimumCount);
                if (minimumCount < 2) {
                    throw new IllegalArgumentException("Un fractionnement se compte a partir de "
                        + "deux operations : " + minimumCount);
                }
            }
            case ATYPICAL_ACTIVITY -> requireAll(method, windowDays, ratio);
            case DORMANT_REACTIVATION -> requireAll(method, thresholdAmount);
        }
    }

    private static void requireAll(Method method, Object... values) {
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("La methode " + method + " exige tous ses "
                    + "parametres : un scenario incomplet ne surveille rien, et personne ne s'en "
                    + "apercoit");
            }
        }
    }

    public static UUID declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO monitoring_scenario(id, legal_entity_id, code, label, method,"
            + " threshold_amount, window_days, minimum_count, ratio, risk_rating, valid_from,"
            + " valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setString(5, draft.method().name());
            setDecimal(ps, 6, draft.thresholdAmount());
            setInt(ps, 7, draft.windowDays());
            setInt(ps, 8, draft.minimumCount());
            setDecimal(ps, 9, draft.ratio());
            ps.setString(10, draft.riskRating());
            ps.setObject(11, draft.validFrom());
            ps.setObject(12, draft.validTo());
            ps.setObject(13, draft.createdBy());
            ps.setObject(14, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Un scenario porte deja le code " + draft.code()
                                                + " a compter du " + draft.validFrom(), e);
            }
            throw new LedgerStoreException("Declaration du scenario de surveillance", e);
        }
        return id;
    }

    /** Les scenarios en vigueur a une date : ce que la banque regarde ce jour-la. */
    public static List<Scenario> activeOn(Connection c, UUID legalEntityId, LocalDate on) {
        List<Scenario> scenarios = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, code, label, method, threshold_amount, window_days,"
            + " minimum_count, ratio, risk_rating, valid_from, valid_to FROM monitoring_scenario"
            + " WHERE legal_entity_id = ? AND valid_from <= ?"
            + "   AND (valid_to IS NULL OR valid_to >= ?) ORDER BY code")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, on);
            ps.setObject(3, on);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scenarios.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Scenarios de surveillance en vigueur", e);
        }
        return scenarios;
    }

    public static List<Scenario> all(Connection c, UUID legalEntityId) {
        List<Scenario> scenarios = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, code, label, method, threshold_amount, window_days,"
            + " minimum_count, ratio, risk_rating, valid_from, valid_to FROM monitoring_scenario"
            + " WHERE legal_entity_id = ? ORDER BY code, valid_from DESC")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scenarios.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Scenarios de surveillance", e);
        }
        return scenarios;
    }

    private static Scenario read(ResultSet rs) throws SQLException {
        return new Scenario(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getString(4), Method.valueOf(rs.getString(5)),
                            rs.getBigDecimal(6), (Integer) rs.getObject(7),
                            (Integer) rs.getObject(8), rs.getBigDecimal(9), rs.getString(10),
                            rs.getObject(11, LocalDate.class), rs.getObject(12, LocalDate.class));
    }

    private static void setDecimal(PreparedStatement ps, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
