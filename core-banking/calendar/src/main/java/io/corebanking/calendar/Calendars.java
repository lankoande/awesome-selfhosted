package io.corebanking.calendar;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Chargement du calendrier et des conditions de date de valeur d'une entite. */
public final class Calendars {

    private Calendars() {}

    // ------------------------------------------------------------------ ecriture

    public static UUID createCalendar(Connection c, String code, String label,
                                      Set<DayOfWeek> weekend, LocalDate from, LocalDate to) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO business_calendar(id, code, label, covers_from, covers_to)"
            + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setString(2, code);
            ps.setString(3, label);
            ps.setObject(4, from);
            ps.setObject(5, to);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du calendrier " + code, e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO calendar_weekend(calendar_id, day_of_week) VALUES (?,?)")) {
            for (DayOfWeek day : weekend) {
                ps.setObject(1, id);
                ps.setInt(2, day.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Declaration du week-end", e);
        }
        return id;
    }

    public static void addHoliday(Connection c, UUID calendarId, LocalDate date, String label) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO calendar_holiday(calendar_id, holiday_date, label) VALUES (?,?,?)"
            + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, calendarId);
            ps.setObject(2, date);
            ps.setString(3, label);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Declaration du ferie du " + date, e);
        }
    }

    public static void attachToEntity(Connection c, UUID legalEntityId, UUID calendarId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE legal_entity SET business_calendar_id = ? WHERE id = ?")) {
            ps.setObject(1, calendarId);
            ps.setObject(2, legalEntityId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rattachement du calendrier a l'entite", e);
        }
    }

    /**
     * L'horloge qui dit si l'heure limite d'un canal est passee. Celle du systeme, sauf pour un
     * test ou une simulation qui la fixe ; le fuseau est celui de l'entite, pas de l'horloge.
     */
    private static volatile Clock clock = Clock.systemUTC();

    public static Clock clock() {
        return clock;
    }

    public static void useClock(Clock replacement) {
        clock = java.util.Objects.requireNonNull(replacement, "clock");
    }

    /** Heure limite d'un canal, validee a deux comme une condition de banque. */
    public static UUID addCutoff(Connection c, UUID legalEntityId, ChannelCutoff cutoff,
                                 UUID createdBy, UUID approvedBy) {
        if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
            throw new IllegalArgumentException(
                "Une heure limite se declare a deux : le demandeur ne peut pas etre le valideur");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO channel_cutoff(id, legal_entity_id, channel, cutoff_time, closes_channel,"
            + " valid_from, valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setString(3, cutoff.channel());
            ps.setObject(4, cutoff.cutoffTime());
            ps.setBoolean(5, cutoff.closesChannel());
            ps.setObject(6, cutoff.validFrom());
            ps.setObject(7, cutoff.validTo());
            ps.setObject(8, createdBy);
            ps.setObject(9, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                throw new IllegalStateException("Une heure limite couvre deja le canal "
                    + (cutoff.channel() == null ? "(tous)" : cutoff.channel())
                    + " sur une partie de la periode", e);
            }
            throw new LedgerStoreException("Declaration de l'heure limite du canal", e);
        }
        return id;
    }

    public static UUID addRule(Connection c, UUID legalEntityId, ValueDateRule rule,
                               UUID createdBy, UUID approvedBy) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO value_date_rule(id, legal_entity_id, operation_type, channel, direction,"
            + " offset_days, offset_unit, convention, valid_from, valid_to, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setString(3, rule.operationType());
            ps.setString(4, rule.channel());
            ps.setString(5, rule.direction().name());
            ps.setInt(6, rule.offset());
            ps.setString(7, rule.unit().name());
            ps.setString(8, rule.convention().name());
            ps.setObject(9, rule.validFrom());
            ps.setObject(10, rule.validTo());
            ps.setObject(11, createdBy);
            ps.setObject(12, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Enregistrement de la condition de date de valeur " + rule.operationType(), e);
        }
        return id;
    }

    // ------------------------------------------------------------------ lecture

    /**
     * Charge le calendrier et les conditions d'une entite.
     *
     * <p>Un chargement pour tout un traitement, pas un par operation : le calendrier tient en
     * memoire et ne change pas en cours d'arrete.
     */
    public static ValueDatePolicy load(Database database, UUID legalEntityId) {
        return database.inTransaction(c -> new ValueDatePolicy(
            loadCalendar(c, legalEntityId), loadRules(c, legalEntityId),
            loadCutoffs(c, legalEntityId), timezoneOf(c, legalEntityId), clock));
    }

    /** Le fuseau de l'entite : celui dans lequel ses heures limites se lisent. */
    public static ZoneId timezoneOf(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT timezone FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite juridique inconnue : " + legalEntityId);
                }
                return ZoneId.of(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du fuseau de l'entite", e);
        }
    }

    private static List<ChannelCutoff> loadCutoffs(Connection c, UUID legalEntityId) {
        List<ChannelCutoff> cutoffs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT channel, cutoff_time, closes_channel, valid_from, valid_to FROM channel_cutoff"
            + " WHERE legal_entity_id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cutoffs.add(new ChannelCutoff(rs.getString(1), rs.getObject(2, LocalTime.class),
                                                  rs.getBoolean(3), rs.getObject(4, LocalDate.class),
                                                  rs.getObject(5, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des heures limites", e);
        }
        return cutoffs;
    }

    private static BusinessCalendar loadCalendar(Connection c, UUID legalEntityId) {
        UUID calendarId;
        String code;
        LocalDate from;
        LocalDate to;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT cal.id, cal.code, cal.covers_from, cal.covers_to FROM legal_entity e"
            + " JOIN business_calendar cal ON cal.id = e.business_calendar_id WHERE e.id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException(
                        "Aucun calendrier rattache a l'entite " + legalEntityId
                        + ". Sans calendrier, aucune date de valeur ne peut etre determinee.");
                }
                calendarId = rs.getObject(1, UUID.class);
                code = rs.getString(2);
                from = rs.getObject(3, LocalDate.class);
                to = rs.getObject(4, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du calendrier de l'entite", e);
        }

        Set<DayOfWeek> weekend = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT day_of_week FROM calendar_weekend WHERE calendar_id = ?")) {
            ps.setObject(1, calendarId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    weekend.add(DayOfWeek.of(rs.getInt(1)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du week-end", e);
        }

        Set<LocalDate> holidays = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT holiday_date FROM calendar_holiday WHERE calendar_id = ? ORDER BY holiday_date")) {
            ps.setObject(1, calendarId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    holidays.add(rs.getObject(1, LocalDate.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des jours feries", e);
        }
        return new BusinessCalendar(code, weekend, holidays, from, to);
    }

    private static List<ValueDateRule> loadRules(Connection c, UUID legalEntityId) {
        List<ValueDateRule> rules = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT operation_type, channel, direction, offset_days, offset_unit, convention,"
            + " valid_from, valid_to FROM value_date_rule WHERE legal_entity_id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(new ValueDateRule(
                        rs.getString(1), rs.getString(2), Direction.valueOf(rs.getString(3)),
                        rs.getInt(4), OffsetUnit.valueOf(rs.getString(5)),
                        BusinessDayConvention.valueOf(rs.getString(6)),
                        rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des conditions de date de valeur", e);
        }
        return rules;
    }

    /** Le calendrier rattache a une entite, s'il y en a un. */
    public static java.util.Optional<UUID> calendarIdOf(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT business_calendar_id FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.ofNullable(rs.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du calendrier de l'entite", e);
        }
    }
}
