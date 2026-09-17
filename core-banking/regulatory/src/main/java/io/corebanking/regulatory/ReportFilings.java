package io.corebanking.regulatory;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Les etats produits : leur contenu fige, leur transmission, leur preuve de depot.
 *
 * <p><b>Un etat produit est fige.</b> Il porte ses lignes et le parametrage sous lequel il a ete
 * produit — le seuil du jour de la production, pas celui d'aujourd'hui. Sans cela, un etat
 * regenere six mois plus tard sortirait different sans qu'on puisse dire si ce sont les donnees
 * ou le parametrage qui ont bouge, et c'est precisement la question que pose l'inspection.
 *
 * <p><b>Une seule verite par periode.</b> Deux etats transmis pour le meme mois seraient deux
 * declarations contradictoires ; la base l'interdit. Pour reprendre un etat, on annule le
 * precedent en le motivant — et un etat deja transmis ne s'annule pas : ce qui est parti est
 * parti, on depose un rectificatif, on ne reecrit pas l'histoire.
 *
 * <p><b>La transmission se fait a deux.</b> Produire est un travail ; transmettre est un
 * engagement devant le superviseur, et il porte la reference que celui-ci a rendue.
 */
public final class ReportFilings {

    private ReportFilings() {}

    public enum Status { PRODUCED, TRANSMITTED, CANCELLED }

    public enum SubjectKind { PARTY, ACCOUNT, GL_ACCOUNT }

    /** Une ligne de l'etat : ce qu'elle designe, ce qu'elle porte. */
    public record Line(SubjectKind subjectKind, UUID subjectId, String subjectReference,
                       String label, Money amount, Money offBalance, String classification,
                       Integer daysPastDue, Integer occurrences, String detail) {

        public static Line of(SubjectKind kind, UUID id, String reference, String label,
                              Money amount) {
            return new Line(kind, id, reference, label, amount, null, null, null, null, null);
        }
    }

    public record Filing(UUID id, UUID legalEntityId, UUID declarationId, String declarationCode,
                         RegulatoryDeclarations.Method method, LocalDate periodStart,
                         LocalDate periodEnd, LocalDate dueOn, LocalDate producedOn,
                         BigDecimal thresholdUsed, int lineCount, Money totalAmount, Status status,
                         LocalDate transmittedOn, String transmissionReference,
                         LocalDate cancelledOn, String cancellationReason, List<Line> lines) {

        public boolean transmitted() {
            return status == Status.TRANSMITTED;
        }

        /** En retard : l'echeance est passee et rien n'est parti. */
        public boolean overdue(LocalDate on) {
            return status != Status.TRANSMITTED && on.isAfter(dueOn);
        }
    }

    /** Production ou transmission refusee. */
    public static class FilingRefusedException extends RuntimeException {
        public FilingRefusedException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ production

    /**
     * Fige un etat pour une periode.
     *
     * <p>La periode est celle de la declaration : une fin de mois pour une declaration mensuelle,
     * de trimestre pour une trimestrielle. Une date qui ne ferme pas de periode est refusee — un
     * etat « du 12 au 12 » ne veut rien dire pour celui qui le recoit.
     */
    public static UUID produce(Connection c, RegulatoryDeclarations.Declaration declaration,
                               LocalDate periodEnd, List<Line> lines, CurrencyRef currency,
                               LocalDate producedOn, UUID producedBy) {
        LocalDate periodStart = declaration.frequency().startOfPeriodEndingOn(periodEnd)
            .orElseThrow(() -> new FilingRefusedException("Le " + periodEnd + " ne ferme pas de "
                + "periode " + declaration.frequency() + " : un etat se produit sur la periode "
                + "que le superviseur attend, pas sur un intervalle choisi"));
        if (periodStart.isBefore(declaration.validFrom())) {
            throw new FilingRefusedException("La declaration " + declaration.code() + " n'est en "
                + "vigueur qu'a partir du " + declaration.validFrom() + " : la periode commencant "
                + "le " + periodStart + " lui est anterieure");
        }
        if (producedOn.isBefore(periodEnd)) {
            throw new FilingRefusedException("Un etat se produit apres la fin de la periode qu'il "
                + "couvre : " + producedOn + " precede le " + periodEnd);
        }
        UUID id = Ids.newId();
        Money total = lines.stream().map(Line::amount).reduce(Money.zero(currency), Money::plus);
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO report_filing(id, legal_entity_id, declaration_id, declaration_code,"
            + " period_start, period_end, due_on, produced_on, threshold_used, method,"
            + " line_count, total_amount, currency, produced_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, declaration.legalEntityId());
            ps.setObject(3, declaration.id());
            ps.setString(4, declaration.code());
            ps.setObject(5, periodStart);
            ps.setObject(6, periodEnd);
            ps.setObject(7, declaration.dueOn(periodEnd));
            ps.setObject(8, producedOn);
            if (declaration.thresholdAmount() == null) {
                ps.setNull(9, Types.NUMERIC);
            } else {
                ps.setBigDecimal(9, declaration.thresholdAmount());
            }
            ps.setString(10, declaration.method().name());
            ps.setInt(11, lines.size());
            ps.setBigDecimal(12, total.amount());
            ps.setString(13, currency.code());
            ps.setObject(14, producedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new FilingRefusedException("Un etat " + declaration.code() + " existe deja "
                    + "pour la periode du " + periodStart + " au " + periodEnd + " : deux etats "
                    + "pour une periode seraient deux verites. L'annuler d'abord, en le motivant.");
            }
            throw new LedgerStoreException("Production de l'etat " + declaration.code(), e);
        }
        insertLines(c, id, lines);
        return id;
    }

    private static void insertLines(Connection c, UUID filingId, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO report_filing_line(filing_id, line_no, subject_kind, subject_id,"
            + " subject_reference, label, amount, currency, off_balance, classification,"
            + " days_past_due, occurrences, detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int no = 1;
            for (Line line : lines) {
                ps.setObject(1, filingId);
                ps.setInt(2, no++);
                ps.setString(3, line.subjectKind().name());
                ps.setObject(4, line.subjectId());
                ps.setString(5, line.subjectReference());
                ps.setString(6, line.label());
                ps.setBigDecimal(7, line.amount().amount());
                ps.setString(8, line.amount().currency().code());
                if (line.offBalance() == null) {
                    ps.setNull(9, Types.NUMERIC);
                } else {
                    ps.setBigDecimal(9, line.offBalance().amount());
                }
                ps.setString(10, line.classification());
                setInt(ps, 11, line.daysPastDue());
                setInt(ps, 12, line.occurrences());
                ps.setString(13, line.detail());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Lignes de l'etat", e);
        }
    }

    // ------------------------------------------------------------------ transmission

    public static Filing transmit(Connection c, UUID filingId, LocalDate on, String reference,
                                  UUID transmittedBy, UUID approvedBy) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("La transmission porte la reference rendue par le "
                + "destinataire : c'est elle qui prouve le depot");
        }
        if (approvedBy == null || approvedBy.equals(transmittedBy)) {
            throw new IllegalArgumentException("Une transmission se fait a deux : produire est un "
                + "travail, transmettre est un engagement devant le superviseur");
        }
        lock(c, filingId);
        Filing filing = require(c, filingId);
        if (filing.status() != Status.PRODUCED) {
            throw new FilingRefusedException("L'etat " + filing.declarationCode() + " du "
                + filing.periodEnd() + " est " + filing.status()
                + (filing.transmitted() ? " depuis le " + filing.transmittedOn() : "")
                + " : il ne se transmet pas deux fois");
        }
        if (on.isBefore(filing.producedOn())) {
            throw new FilingRefusedException("Un etat ne se transmet pas avant d'etre produit : "
                + on + " precede le " + filing.producedOn());
        }
        update(c, "UPDATE report_filing SET status = 'TRANSMITTED', transmitted_on = ?,"
                  + " transmission_reference = ?, transmitted_by = ?, approved_by = ?"
                  + " WHERE id = ?", ps -> {
            ps.setObject(1, on);
            ps.setString(2, reference.trim());
            ps.setObject(3, transmittedBy);
            ps.setObject(4, approvedBy);
            ps.setObject(5, filingId);
        });
        return require(c, filingId);
    }

    /**
     * Annule un etat produit et non transmis.
     *
     * <p>Ce qui est parti est parti : un etat transmis ne s'annule pas, il se rectifie par un
     * depot suivant. L'annuler en base effacerait la trace de ce que la banque a declare, et
     * c'est exactement ce que l'inspection vient verifier.
     */
    public static Filing cancel(Connection c, UUID filingId, LocalDate on, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("L'annulation d'un etat porte son motif");
        }
        lock(c, filingId);
        Filing filing = require(c, filingId);
        if (filing.status() != Status.PRODUCED) {
            throw new FilingRefusedException("L'etat " + filing.declarationCode() + " du "
                + filing.periodEnd() + " est " + filing.status() + " : "
                + (filing.transmitted()
                   ? "ce qui est transmis ne s'annule pas, il se rectifie par un depot suivant"
                   : "il est deja annule"));
        }
        update(c, "UPDATE report_filing SET status = 'CANCELLED', cancelled_on = ?,"
                  + " cancellation_reason = ? WHERE id = ?", ps -> {
            ps.setObject(1, on);
            ps.setString(2, reason.trim());
            ps.setObject(3, filingId);
        });
        return require(c, filingId);
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT f.id, f.legal_entity_id, f.declaration_id, f.declaration_code, f.method,"
        + " f.period_start, f.period_end, f.due_on, f.produced_on, f.threshold_used,"
        + " f.line_count, f.total_amount, f.currency, f.status, f.transmitted_on,"
        + " f.transmission_reference, f.cancelled_on, f.cancellation_reason, cur.scale,"
        + " cur.rounding_mode FROM report_filing f JOIN currency cur ON cur.code = f.currency";

    public static Filing require(Connection c, UUID filingId) {
        return find(c, filingId).orElseThrow(
            () -> new IllegalArgumentException("Etat inconnu : " + filingId));
    }

    public static Optional<Filing> find(Connection c, UUID filingId) {
        List<Filing> found = query(c, SELECT + " WHERE f.id = ?",
                                   ps -> ps.setObject(1, filingId), true);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** Les etats d'une entite, sans leurs lignes : la liste de travail du declarant. */
    public static List<Filing> filings(Connection c, UUID legalEntityId, String status) {
        return query(c, SELECT + " WHERE f.legal_entity_id = ?"
                        + "   AND (?::text IS NULL OR f.status = ?)"
                        + " ORDER BY f.period_end DESC, f.declaration_code", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
        }, false);
    }

    /** L'etat en vigueur d'une declaration pour une periode, s'il y en a un. */
    public static Optional<Filing> forPeriod(Connection c, UUID declarationId,
                                             LocalDate periodEnd) {
        List<Filing> found = query(c, SELECT + " WHERE f.declaration_id = ? AND f.period_end = ?"
                                      + "   AND f.status <> 'CANCELLED'", ps -> {
            ps.setObject(1, declarationId);
            ps.setObject(2, periodEnd);
        }, false);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    public static List<Line> lines(Connection c, UUID filingId) {
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT l.subject_kind, l.subject_id, l.subject_reference, l.label, l.amount,"
            + " l.currency, l.off_balance, l.classification, l.days_past_due, l.occurrences,"
            + " l.detail, cur.scale, cur.rounding_mode FROM report_filing_line l"
            + "  JOIN currency cur ON cur.code = l.currency"
            + " WHERE l.filing_id = ? ORDER BY l.line_no")) {
            ps.setObject(1, filingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(6), rs.getInt(12),
                        RoundingMode.valueOf(rs.getString(13)));
                    BigDecimal off = rs.getBigDecimal(7);
                    lines.add(new Line(SubjectKind.valueOf(rs.getString(1)),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        Money.of(rs.getBigDecimal(5), currency).roundToCurrency(),
                        off == null ? null : Money.of(off, currency).roundToCurrency(),
                        rs.getString(8), integer(rs, 9), integer(rs, 10), rs.getString(11)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lignes de l'etat " + filingId, e);
        }
        return lines;
    }

    // ------------------------------------------------------------------ outillage

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Filing> query(Connection c, String sql, Binder binder, boolean withLines) {
        List<Filing> filings = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    CurrencyRef currency = new CurrencyRef(rs.getString(13), rs.getInt(19),
                        RoundingMode.valueOf(rs.getString(20)));
                    filings.add(new Filing(id, rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        RegulatoryDeclarations.Method.valueOf(rs.getString(5)),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                        rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class),
                        rs.getBigDecimal(10), rs.getInt(11),
                        Money.of(rs.getBigDecimal(12), currency).roundToCurrency(),
                        Status.valueOf(rs.getString(14)), rs.getObject(15, LocalDate.class),
                        rs.getString(16), rs.getObject(17, LocalDate.class), rs.getString(18),
                        withLines ? lines(c, id) : List.of()));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Etats reglementaires", e);
        }
        return filings;
    }

    private static void lock(Connection c, UUID filingId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM report_filing WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, filingId);
            ps.executeQuery().close();
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou sur l'etat " + filingId, e);
        }
    }

    private static void update(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mise a jour de l'etat", e);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Integer integer(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? null : value;
    }
}
