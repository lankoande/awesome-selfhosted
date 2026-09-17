package io.corebanking.compliance;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Declaration de soupcon : le dossier transmis a la cellule de renseignement financier.
 *
 * <p><b>Elle se decide a deux.</b> Declarer engage la banque et met en cause une personne ; ne pas
 * declarer engage la banque autant. Aucun des deux sens n'est une decision qu'on prend seul.
 *
 * <p><b>Elle cite les alertes qu'elle couvre.</b> Une declaration qui ne renverrait a rien serait
 * indefendable devant l'inspection ; et une alerte declaree deux fois ferait deux dossiers pour un
 * seul fait. Les alertes citees passent a {@code REPORTED} et n'en ressortent pas : leur sort est
 * scelle par la declaration, pas par un classement.
 *
 * <p><b>Elle est secrete.</b> Rien de ce qui est ici ne remonte au dossier client, ni a aucune
 * lecture d'agence : informer la personne declaree est un delit, et le socle ne doit pas offrir le
 * chemin qui le rendrait possible par inadvertance.
 */
public final class SuspiciousActivityReports {

    private SuspiciousActivityReports() {}

    public record Report(UUID id, UUID legalEntityId, UUID partyId, String reference,
                         LocalDate draftedOn, String narrative, LocalDate transmittedOn,
                         String transmissionReference, List<UUID> alertIds) {

        public boolean transmitted() {
            return transmittedOn != null;
        }
    }

    public record Draft(UUID legalEntityId, UUID partyId, String reference, LocalDate draftedOn,
                        String narrative, List<UUID> alertIds, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(draftedOn, "draftedOn");
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("Une declaration porte sa reference");
            }
            if (narrative == null || narrative.isBlank()) {
                throw new IllegalArgumentException("Une declaration porte son expose des faits : "
                    + "c'est lui que la cellule lira, pas la liste des alertes");
            }
            if (alertIds == null || alertIds.isEmpty()) {
                throw new IllegalArgumentException("Une declaration cite les alertes qu'elle "
                    + "couvre : sans elles, rien ne la rattache a des faits");
            }
            alertIds = List.copyOf(alertIds);
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une declaration de soupcon se decide a deux : "
                    + "declarer engage la banque, ne pas declarer aussi");
            }
        }
    }

    /** Declaration refusee. */
    public static class ReportRefusedException extends RuntimeException {
        public ReportRefusedException(String message) {
            super(message);
        }
    }

    public static UUID draft(Connection c, Draft draft) {
        UUID id = Ids.newId();
        for (UUID alertId : draft.alertIds()) {
            AmlAlerts.Alert alert = AmlAlerts.require(c, alertId);
            if (!alert.legalEntityId().equals(draft.legalEntityId())
                || !alert.partyId().equals(draft.partyId())) {
                throw new ReportRefusedException("L'alerte " + alertId + " ne porte pas sur le "
                    + "tiers declare : une declaration ne melange pas deux dossiers");
            }
            if ("REPORTED".equals(alert.status())) {
                throw new ReportRefusedException("L'alerte " + alertId + " est deja couverte par "
                    + "une declaration : deux dossiers pour un seul fait");
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO suspicious_activity_report(id, legal_entity_id, party_id, reference,"
            + " drafted_on, narrative, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setObject(3, draft.partyId());
            ps.setString(4, draft.reference());
            ps.setObject(5, draft.draftedOn());
            ps.setString(6, draft.narrative());
            ps.setObject(7, draft.createdBy());
            ps.setObject(8, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Une declaration porte deja la reference "
                                                + draft.reference(), e);
            }
            throw new LedgerStoreException("Redaction de la declaration de soupcon", e);
        }
        // Les alertes citees passent a REPORTED : leur sort est scelle par la declaration.
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE aml_alert SET status = 'REPORTED', report_id = ?, closed_on = ?,"
            + " closure_reason = ?, closed_by = ? WHERE id = ?")) {
            for (UUID alertId : draft.alertIds()) {
                ps.setObject(1, id);
                ps.setObject(2, draft.draftedOn());
                ps.setString(3, "declaration de soupcon " + draft.reference());
                ps.setObject(4, draft.approvedBy());
                ps.setObject(5, alertId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rattachement des alertes a la declaration", e);
        }
        return id;
    }

    /** Transmission effective a la cellule : la date et la reference qu'elle a rendue. */
    public static Report transmit(Connection c, UUID reportId, LocalDate on, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("La transmission porte la reference rendue par la "
                + "cellule : c'est elle qui prouve le depot");
        }
        Report report = require(c, reportId);
        if (report.transmitted()) {
            throw new ReportRefusedException("La declaration " + report.reference()
                + " a deja ete transmise le " + report.transmittedOn());
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE suspicious_activity_report SET transmitted_on = ?,"
            + " transmission_reference = ? WHERE id = ?")) {
            ps.setObject(1, on);
            ps.setString(2, reference.trim());
            ps.setObject(3, reportId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Transmission de la declaration", e);
        }
        return require(c, reportId);
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT id, legal_entity_id, party_id, reference, drafted_on, narrative, transmitted_on,"
        + " transmission_reference FROM suspicious_activity_report";

    public static Report require(Connection c, UUID reportId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Declaration inconnue : " + reportId);
                }
                return read(c, rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la declaration " + reportId, e);
        }
    }

    public static List<Report> reports(Connection c, UUID legalEntityId) {
        List<Report> reports = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY drafted_on DESC, reference")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reports.add(read(c, rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Declarations de l'entite", e);
        }
        return reports;
    }

    private static Report read(Connection c, ResultSet rs) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        List<UUID> alerts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM aml_alert WHERE report_id = ? ORDER BY raised_on")) {
            ps.setObject(1, id);
            try (ResultSet items = ps.executeQuery()) {
                while (items.next()) {
                    alerts.add(items.getObject(1, UUID.class));
                }
            }
        }
        return new Report(id, rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                          rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6),
                          rs.getObject(7, LocalDate.class), rs.getString(8), alerts);
    }
}
