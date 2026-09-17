package io.corebanking.regulatory;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Le perimetre de consolidation : qui entre dans les comptes du groupe, et comment.
 *
 * <p><b>Le perimetre se declare, il ne se devine pas.</b> Une filiale detenue a 51 % et une
 * participation de 20 % ne se traitent pas de la meme facon, et la difference n'est lisible nulle
 * part dans les comptes : elle vient d'un pacte, d'un conseil, d'une decision. C'est donc du
 * parametrage, pose a deux.
 *
 * <p><b>Ce qui se fait face s'elimine.</b> La creance d'une entite du groupe sur une autre est la
 * dette de celle-ci : les agreger sans les eliminer gonflerait le bilan du groupe de sommes qu'il
 * se doit a lui-meme. C'est la premiere chose que regarde un commissaire aux comptes, et le socle
 * ne les devine pas non plus — les comptes qui se font face sont declares par paires, et
 * l'agregation verifie qu'ils se repondent avant de les eliminer.
 */
public final class ConsolidationScopes {

    private ConsolidationScopes() {}

    /** Comment l'entite entre dans les comptes du groupe. */
    public enum Method {
        /** Integration globale : tout est repris. */
        FULL,
        /** Integration proportionnelle : la quote-part de chaque poste. */
        PROPORTIONAL,
        /**
         * Mise en equivalence : rien n'est agrege. Le socle la nomme et ne l'agrege pas, plutot
         * que de presenter une quote-part de situation nette qu'il ne sait pas encore calculer.
         */
        EQUITY
    }

    public record Member(UUID entityId, Method method, BigDecimal interestPercent) {}

    /** Deux comptes qui se font face d'une entite a l'autre. */
    public record Elimination(UUID id, String label, UUID leftEntityId, UUID leftAccountId,
                              UUID rightEntityId, UUID rightAccountId) {}

    public record Scope(UUID id, UUID legalEntityId, String code, String label,
                        String presentationCurrency, List<Member> members,
                        List<Elimination> eliminations, LocalDate validFrom, LocalDate validTo) {

        public Scope {
            members = List.copyOf(members == null ? List.of() : members);
            eliminations = List.copyOf(eliminations == null ? List.of() : eliminations);
        }
    }

    public record Draft(UUID legalEntityId, String code, String label, String presentationCurrency,
                        List<Member> members, LocalDate validFrom, LocalDate validTo,
                        UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(validFrom, "validFrom");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Un perimetre porte son code");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Un perimetre porte son libelle");
            }
            if (presentationCurrency == null || presentationCurrency.length() != 3) {
                throw new IllegalArgumentException("Un perimetre porte sa devise de presentation : "
                    + "chaque entite tient ses comptes dans la sienne, le groupe en retient une");
            }
            members = List.copyOf(members == null ? List.of() : members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("Un perimetre de consolidation a des membres");
            }
            Set<UUID> seen = new HashSet<>();
            boolean consolidating = false;
            for (Member member : members) {
                Objects.requireNonNull(member.entityId(), "entityId");
                Objects.requireNonNull(member.method(), "method");
                if (!seen.add(member.entityId())) {
                    throw new IllegalArgumentException("L'entite " + member.entityId()
                        + " figure deux fois au perimetre : elle serait consolidee deux fois");
                }
                if (member.interestPercent() == null || member.interestPercent().signum() <= 0
                    || member.interestPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("Un pourcentage d'interet va de 0 exclu a "
                        + "100 : " + member.interestPercent());
                }
                if (member.method() == Method.FULL
                    && member.interestPercent().compareTo(BigDecimal.valueOf(100)) != 0) {
                    throw new IllegalArgumentException("L'integration globale reprend tout : une "
                        + "quote-part de " + member.interestPercent() + " % laisserait des "
                        + "interets minoritaires que le socle ne sait pas encore presenter");
                }
                consolidating |= member.entityId().equals(legalEntityId);
            }
            // L'entite qui publie fait partie de ce qu'elle publie : sans elle, l'etat consolide
            // presenterait le groupe sans sa tete.
            if (!consolidating) {
                throw new IllegalArgumentException("L'entite consolidante figure a son propre "
                    + "perimetre : sans elle, l'etat presenterait le groupe sans sa tete");
            }
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Un perimetre ne cesse pas avant de "
                    + "commencer : " + validFrom + " a " + validTo);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Un perimetre de consolidation se declare a "
                    + "deux : il decide de ce que le groupe presente comme sien");
            }
        }
    }

    public static UUID declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO consolidation_scope(id, legal_entity_id, code, label,"
            + " presentation_currency, valid_from, valid_to, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setString(5, draft.presentationCurrency());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.validTo());
            ps.setObject(8, draft.createdBy());
            ps.setObject(9, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Un perimetre " + draft.code()
                    + " est deja en vigueur au " + draft.validFrom(), e);
            }
            throw new LedgerStoreException("Declaration d'un perimetre de consolidation", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO consolidation_member(scope_id, member_entity_id, method,"
            + " interest_percent) VALUES (?,?,?,?)")) {
            for (Member member : draft.members()) {
                ps.setObject(1, id);
                ps.setObject(2, member.entityId());
                ps.setString(3, member.method().name());
                ps.setBigDecimal(4, member.interestPercent());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Membres du perimetre", e);
        }
        return id;
    }

    /** Declare deux comptes qui se font face : ils s'elimineront, apres s'etre repondu. */
    public static UUID eliminate(Connection c, UUID scopeId, String label, UUID leftEntityId,
                                 UUID leftAccountId, UUID rightEntityId, UUID rightAccountId) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Une elimination porte son libelle : c'est lui qui "
                + "dit ce qui se fait face");
        }
        if (Objects.equals(leftEntityId, rightEntityId)) {
            throw new IllegalArgumentException("Une elimination oppose deux entites differentes : "
                + "a l'interieur d'une entite, c'est une liaison, et elle est deja eliminee");
        }
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO consolidation_elimination(id, scope_id, label, left_entity_id,"
            + " left_account_id, right_entity_id, right_account_id) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, scopeId);
            ps.setString(3, label);
            ps.setObject(4, leftEntityId);
            ps.setObject(5, leftAccountId);
            ps.setObject(6, rightEntityId);
            ps.setObject(7, rightAccountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Un de ces comptes est deja elimine dans ce "
                    + "perimetre : il ne s'elimine pas deux fois", e);
            }
            throw new LedgerStoreException("Declaration d'une elimination", e);
        }
        return id;
    }

    public static Optional<Scope> activeOn(Connection c, UUID legalEntityId, String code,
                                           LocalDate on) {
        List<Scope> found = query(c, SELECT + " WHERE legal_entity_id = ? AND code = ?"
                                     + "   AND valid_from <= ?"
                                     + "   AND (valid_to IS NULL OR valid_to >= ?)"
                                     + " ORDER BY valid_from DESC", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setObject(3, on);
            ps.setObject(4, on);
        });
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    public static List<Scope> all(Connection c, UUID legalEntityId) {
        return query(c, SELECT + " WHERE legal_entity_id = ? ORDER BY code, valid_from",
                     ps -> ps.setObject(1, legalEntityId));
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, code, label, presentation_currency, valid_from, valid_to"
        + " FROM consolidation_scope";

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Scope> query(Connection c, String sql, Binder binder) {
        List<Scope> scopes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    scopes.add(new Scope(id, rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), members(c, id), eliminations(c, id),
                        rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Perimetres de consolidation", e);
        }
        return scopes;
    }

    private static List<Member> members(Connection c, UUID scopeId) {
        List<Member> members = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT member_entity_id, method, interest_percent FROM consolidation_member"
            + " WHERE scope_id = ? ORDER BY member_entity_id")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(new Member(rs.getObject(1, UUID.class),
                        Method.valueOf(rs.getString(2)), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Membres du perimetre " + scopeId, e);
        }
        return members;
    }

    private static List<Elimination> eliminations(Connection c, UUID scopeId) {
        List<Elimination> eliminations = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, label, left_entity_id, left_account_id, right_entity_id,"
            + " right_account_id FROM consolidation_elimination WHERE scope_id = ?"
            + " ORDER BY label")) {
            ps.setObject(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    eliminations.add(new Elimination(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getObject(6, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Eliminations du perimetre " + scopeId, e);
        }
        return eliminations;
    }
}
