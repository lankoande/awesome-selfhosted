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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // ------------------------------------------------------------------ relecture du parametrage

    /** En-tete d'un schema : ce qui se lit dans une liste, sans ouvrir le detail. */
    public record Summary(UUID id, String code, String label, String currency,
                          LocalDate validFrom, LocalDate validTo, String status,
                          UUID createdBy, Instant createdAt, UUID approvedBy, Instant approvedAt,
                          UUID withdrawnBy, Instant withdrawnAt) {}

    /** Une derivation telle qu'elle a ete ecrite, dans son ordre d'evaluation. */
    public record DerivationView(String name, String expression) {}

    /** Une ligne telle qu'elle a ete ecrite, expressions comprises. */
    public record LineView(String account, String direction, String amount, String condition,
                           String label) {}

    /**
     * Un evenement du schema.
     *
     * @param variables grandeurs que le module doit fournir : referencees et non calculees. Elles
     *                  sont <b>deduites des expressions</b>, jamais saisies : une liste tenue a la
     *                  main a cote des expressions finirait par ne plus les decrire.
     */
    public record EventView(String eventType, List<DerivationView> derivations,
                            List<LineView> lines, List<String> variables) {}

    /** Un schema en entier : son en-tete et ses evenements. */
    public record Detail(Summary header, List<EventView> events) {}

    /**
     * Les schemas de l'entite, brouillons compris.
     *
     * <p>Sans cette lecture, l'identifiant d'un brouillon n'existait que dans la reponse du POST
     * qui l'avait cree : perdu au rechargement, le brouillon devenait inactivable, et le schema
     * en vigueur ne se relisait nulle part.
     */
    public static List<Summary> summaries(Connection c, UUID legalEntityId, String code,
                                          String status) {
        List<Summary> found = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, code, label, currency, valid_from, valid_to, status, created_by,"
            + " created_at, approved_by, approved_at, withdrawn_by, withdrawn_at"
            + "  FROM accounting_schema"
            + " WHERE legal_entity_id = ?"
            + "   AND (?::text IS NULL OR code = ?::text)"
            + "   AND (?::text IS NULL OR status = ?::text)"
            + " ORDER BY code, valid_from DESC, created_at DESC")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setString(3, code);
            ps.setString(4, status);
            ps.setString(5, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(new Summary(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class), rs.getString(7),
                        rs.getObject(8, UUID.class), instant(rs, 9),
                        rs.getObject(10, UUID.class), instant(rs, 11),
                        rs.getObject(12, UUID.class), instant(rs, 13)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des schemas comptables", e);
        }
        return List.copyOf(found);
    }

    /** Un schema en entier, quel que soit son etat. L'entite est verifiee, jamais supposee. */
    public static Optional<Detail> detail(Connection c, UUID legalEntityId, UUID schemaId) {
        List<Summary> found = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, code, label, currency, valid_from, valid_to, status, created_by,"
            + " created_at, approved_by, approved_at, withdrawn_by, withdrawn_at"
            + "  FROM accounting_schema WHERE id = ? AND legal_entity_id = ?")) {
            ps.setObject(1, schemaId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                found.add(new Summary(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getObject(5, LocalDate.class),
                    rs.getObject(6, LocalDate.class), rs.getString(7),
                    rs.getObject(8, UUID.class), instant(rs, 9),
                    rs.getObject(10, UUID.class), instant(rs, 11),
                    rs.getObject(12, UUID.class), instant(rs, 13)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du schema comptable " + schemaId, e);
        }

        Summary header = found.get(0);
        AccountingSchema schema = load(c, header.code(), schemaId);
        List<EventView> events = new ArrayList<>(schema.templates().size());
        for (EventTemplate template : schema.templates().values()) {
            List<DerivationView> derivations = new ArrayList<>();
            template.derivations().forEach((name, expression) ->
                derivations.add(new DerivationView(name, expression.source())));
            List<LineView> lines = new ArrayList<>(template.lines().size());
            for (TemplateLine line : template.lines()) {
                lines.add(new LineView(line.account().toString(), line.direction().name(),
                                       line.amount().source(),
                                       line.condition() == null ? null : line.condition().source(),
                                       line.label()));
            }
            events.add(new EventView(template.eventType(), List.copyOf(derivations),
                                     List.copyOf(lines), List.copyOf(template.freeVariables())));
        }
        return Optional.of(new Detail(header, List.copyOf(events)));
    }

    // ------------------------------------------------------------------ fin de vie

    /**
     * Ferme la validite d'un schema actif.
     *
     * <p>C'est ainsi qu'un schema cesse de s'appliquer, et non par un changement d'etat. Deux
     * raisons, la seconde etant la plus contraignante :
     *
     * <ul>
     *   <li>{@link #resolveAt} ne resout qu'un schema <b>actif</b>, a une date qui peut etre
     *       passee : suspendre un schema changerait l'imputation d'un arrete rejoue ;</li>
     *   <li>la contrainte d'exclusion interdit deux validites actives qui se croisent sous le meme
     *       code. Tant que le schema en vigueur n'a pas de fin, <b>aucun successeur ne peut etre
     *       active</b> : la fermeture est ce qui rend le versionnement possible.</li>
     * </ul>
     *
     * <p>La fermeture ne peut pas preceder la date comptable, pour la meme raison qu'un produit :
     * un arrete deja produit resoudrait un autre parametrage au rejeu, donc d'autres montants.
     *
     * <p>Aucun acteur n'est enregistre ici : la fermeture passe par un second regard, et
     * {@code pending_operation} garde le redacteur comme l'approbateur.
     */
    public static void close(Connection c, UUID legalEntityId, UUID schemaId,
                             LocalDate validTo) {
        LocalDate businessDate = businessDateOf(c, legalEntityId);
        if (validTo.isBefore(businessDate)) {
            throw new IllegalArgumentException(
                "Fermeture au " + validTo + " alors que la banque en est au " + businessDate
                + " : un arrete deja produit resoudrait un autre schema au rejeu, donc d'autres "
                + "imputations. La fermeture prend effet a la date comptable ou apres.");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_schema SET valid_to = ?"
            + " WHERE id = ? AND legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)")) {
            ps.setObject(1, validTo);
            ps.setObject(2, schemaId);
            ps.setObject(3, legalEntityId);
            ps.setObject(4, validTo);
            ps.setObject(5, validTo);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Schema " + schemaId + " introuvable, non actif, ou deja ferme a cette date ou "
                    + "avant. Une fermeture ne raccourcit pas une validite en deca de son entree "
                    + "en vigueur.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Fermeture du schema " + schemaId, e);
        }
    }

    /**
     * Retire un brouillon abandonne. Il n'est pas supprime : ce qui a ete saisi une fois se relit,
     * et un brouillon retire explique pourquoi un schema attendu n'existe pas.
     */
    public static void withdrawDraft(Connection c, UUID legalEntityId, UUID schemaId,
                                     UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE accounting_schema"
            + "   SET status = 'WITHDRAWN', withdrawn_by = ?, withdrawn_at = now()"
            + " WHERE id = ? AND legal_entity_id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, java.util.Objects.requireNonNull(actorId, "actorId"));
            ps.setObject(2, schemaId);
            ps.setObject(3, legalEntityId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Schema " + schemaId + " introuvable ou deja sorti de l'etat brouillon. Un "
                    + "schema active ne se retire pas : sa validite se ferme.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Retrait du brouillon " + schemaId, e);
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        java.sql.Timestamp stamp = rs.getTimestamp(column);
        return stamp == null ? null : stamp.toInstant();
    }

    private static LocalDate businessDateOf(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new LedgerStoreException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la date comptable de l'entite", e);
        }
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

    /** Ce qu'il faut savoir d'un schema avant de decider de son activation. */
    public record Header(UUID id, UUID legalEntityId, String code, String status, UUID createdBy) {}

    public static java.util.Optional<Header> find(Connection c, UUID schemaId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, code, status, created_by FROM accounting_schema"
            + " WHERE id = ?")) {
            ps.setObject(1, schemaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(new Header(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getObject(5, UUID.class))) : java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du schema comptable " + schemaId, e);
        }
    }
}
