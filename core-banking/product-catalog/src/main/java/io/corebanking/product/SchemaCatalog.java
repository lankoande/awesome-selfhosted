package io.corebanking.product;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.schema.AccountRef;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaValidator;
import io.corebanking.schema.TemplateLine;
import io.corebanking.schema.expr.Expression;
import io.corebanking.schema.expr.Expressions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Depot des schemas comptables : creation, activation, resolution datee.
 *
 * <p><b>Un schema desequilibre ne peut pas etre enregistre.</b> La validation par tirage
 * ({@link SchemaValidator}) s'execute avant l'insertion, dans la devise du schema. Refuser au
 * moment de l'enregistrement plutot qu'a l'activation evite qu'un parametrage faux dorme en base,
 * ou quelqu'un finira par l'activer en urgence un soir d'arrete.
 */
public final class SchemaCatalog {

    private SchemaCatalog() {}

    public record Draft(
        UUID legalEntityId,
        String code,
        String label,
        CurrencyRef currency,
        LocalDate validFrom,
        LocalDate validTo,
        AccountingSchema schema,
        UUID createdBy) {}

    public static UUID createDraft(Connection c, Draft draft) {
        // Rejet avant toute ecriture : la base ne contient jamais de schema desequilibre.
        SchemaValidator.validate(draft.schema(), draft.currency());

        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO accounting_schema(id, legal_entity_id, code, label, currency, valid_from,"
            + " valid_to, status, created_by) VALUES (?,?,?,?,?,?,?,'DRAFT',?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setString(5, draft.currency().code());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.validTo());
            ps.setObject(8, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du schema comptable " + draft.code(), e);
        }

        insertTemplates(c, id, draft.schema());
        return id;
    }

    public static void activate(Connection c, UUID schemaId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_schema SET status = 'ACTIVE', approved_by = ?, approved_at = now()"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, schemaId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Schema " + schemaId + " introuvable ou deja sorti de l'etat DRAFT.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation du schema " + schemaId, e);
        }
    }

    /** Schema en vigueur a une date. Une periode non couverte est une erreur, jamais un repli. */
    public static AccountingSchema resolveAt(Connection c, UUID legalEntityId, String code,
                                             LocalDate date) {
        UUID id;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM accounting_schema"
            + " WHERE legal_entity_id = ? AND code = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setObject(3, date);
            ps.setObject(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ProductNotFoundException("schema comptable " + code, date);
                }
                id = rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Resolution du schema " + code, e);
        }
        return load(c, code, id);
    }

    // ------------------------------------------------------------------ interne

    private static void insertTemplates(Connection c, UUID schemaId, AccountingSchema schema) {
        try (PreparedStatement derivations = c.prepareStatement(
                 "INSERT INTO accounting_schema_derivation(schema_id, event_type, derivation_order,"
                 + " name, expression) VALUES (?,?,?,?,?)");
             PreparedStatement lines = c.prepareStatement(
                 "INSERT INTO accounting_schema_line(schema_id, event_type, line_order, account_ref,"
                 + " direction, amount_expr, condition_expr, label) VALUES (?,?,?,?,?,?,?,?)")) {

            for (EventTemplate template : schema.templates().values()) {
                int order = 0;
                for (Map.Entry<String, Expression> derivation : template.derivations().entrySet()) {
                    derivations.setObject(1, schemaId);
                    derivations.setString(2, template.eventType());
                    derivations.setInt(3, order++);
                    derivations.setString(4, derivation.getKey());
                    derivations.setString(5, derivation.getValue().source());
                    derivations.addBatch();
                }
                int lineOrder = 0;
                for (TemplateLine line : template.lines()) {
                    lines.setObject(1, schemaId);
                    lines.setString(2, template.eventType());
                    lines.setInt(3, lineOrder++);
                    lines.setString(4, line.account().toString());
                    lines.setString(5, line.direction().name());
                    lines.setString(6, line.amount().source());
                    lines.setString(7, line.condition() == null ? null
                                                                : line.condition().source());
                    lines.setString(8, line.label());
                    lines.addBatch();
                }
            }
            derivations.executeBatch();
            lines.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du schema comptable", e);
        }
    }

    private static AccountingSchema load(Connection c, String code, UUID schemaId) {
        Map<String, LinkedHashMap<String, Expression>> derivations = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT event_type, name, expression FROM accounting_schema_derivation"
            + " WHERE schema_id = ? ORDER BY event_type, derivation_order")) {
            ps.setObject(1, schemaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    derivations.computeIfAbsent(rs.getString(1), key -> new LinkedHashMap<>())
                        .put(rs.getString(2), Expressions.parse(rs.getString(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des derivations du schema", e);
        }

        Map<String, List<TemplateLine>> lines = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT event_type, account_ref, direction, amount_expr, condition_expr, label"
            + " FROM accounting_schema_line WHERE schema_id = ? ORDER BY event_type, line_order")) {
            ps.setObject(1, schemaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String conditionExpr = rs.getString(5);
                    lines.computeIfAbsent(rs.getString(1), key -> new java.util.ArrayList<>())
                        .add(new TemplateLine(
                            AccountRef.parse(rs.getString(2)),
                            Direction.valueOf(rs.getString(3)),
                            Expressions.parse(rs.getString(4)),
                            rs.getString(6),
                            conditionExpr == null ? null : Expressions.parse(conditionExpr)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des lignes du schema", e);
        }

        AccountingSchema.Builder builder = AccountingSchema.of(code, 1);
        lines.forEach((eventType, eventLines) -> builder.on(new EventTemplate(
            eventType, derivations.getOrDefault(eventType, new LinkedHashMap<>()), eventLines)));
        return builder.build();
    }
}
