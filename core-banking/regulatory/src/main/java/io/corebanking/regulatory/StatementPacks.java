package io.corebanking.regulatory;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.StatementLayouts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * La liasse : un jeu d'etats declares comme un tout.
 *
 * <p><b>Une liasse n'est pas une pile d'etats.</b> C'est un ensemble qui doit se tenir : le
 * resultat que porte le compte de resultat est celui qu'annonce le bilan. S'ils different, ce
 * n'est pas une presentation a corriger, c'est une comptabilite a reprendre — et il vaut mieux
 * l'apprendre avant de transmettre que du superviseur.
 *
 * <p><b>La liasse cite des natures, pas des maquettes.</b> Elle dit « le bilan » ; c'est la
 * maquette active a la date de production qui repond. Citer une maquette par son identifiant
 * obligerait a redeclarer la liasse a chaque nouvelle version de presentation, et une liasse
 * qu'on redeclare souvent est une liasse qu'on finit par ne plus relire.
 */
public final class StatementPacks {

    private StatementPacks() {}

    public record Pack(UUID id, UUID legalEntityId, String code, String label,
                       List<StatementLayouts.Kind> items, LocalDate validFrom, LocalDate validTo) {

        public Pack {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    public record Draft(UUID legalEntityId, String code, String label,
                        List<StatementLayouts.Kind> items, LocalDate validFrom, LocalDate validTo,
                        UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(validFrom, "validFrom");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Une liasse porte son code");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Une liasse porte son libelle");
            }
            items = requireComposition(items);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Une liasse ne cesse pas avant de commencer : "
                    + validFrom + " a " + validTo);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une liasse se declare a deux : elle fixe ce "
                    + "que la banque presente de ses comptes");
            }
        }
    }

    /**
     * Ce qu'une liasse doit citer pour en etre une.
     *
     * <p>Le controle qui fait son interet est le rapprochement du resultat entre le compte de
     * resultat et le bilan. Une liasse qui n'aurait pas les deux ne pourrait pas le faire, et ne
     * serait qu'une pile d'etats. La verification est publique pour etre faite <b>a la
     * soumission</b> : un valideur ne doit pas decouvrir une liasse qui ne rapproche rien.
     */
    public static List<StatementLayouts.Kind> requireComposition(
            List<StatementLayouts.Kind> items) {
        Set<StatementLayouts.Kind> distinct = new LinkedHashSet<>(
            items == null ? List.of() : items);
        if (distinct.size() != (items == null ? 0 : items.size())) {
            throw new IllegalArgumentException("Une liasse ne cite pas deux fois le meme etat : "
                + "lequel des deux serait le bon ?");
        }
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("Une liasse cite les etats qui la composent : sans "
                + "eux, elle ne produit rien");
        }
        if (!distinct.contains(StatementLayouts.Kind.BALANCE_SHEET)
            || !distinct.contains(StatementLayouts.Kind.INCOME_STATEMENT)) {
            throw new IllegalArgumentException("Une liasse porte au moins le bilan et le compte "
                + "de resultat : c'est leur rapprochement qui la rend utile");
        }
        return List.copyOf(distinct);
    }

    public static UUID declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO statement_pack(id, legal_entity_id, code, label, valid_from, valid_to,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setObject(5, draft.validFrom());
            ps.setObject(6, draft.validTo());
            ps.setObject(7, draft.createdBy());
            ps.setObject(8, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Une liasse " + draft.code()
                    + " est deja en vigueur au " + draft.validFrom(), e);
            }
            throw new LedgerStoreException("Declaration d'une liasse", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO statement_pack_item(pack_id, ordinal, kind) VALUES (?,?,?)")) {
            int ordinal = 1;
            for (StatementLayouts.Kind kind : draft.items()) {
                ps.setObject(1, id);
                ps.setInt(2, ordinal++);
                ps.setString(3, kind.name());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Composition de la liasse", e);
        }
        return id;
    }

    public static Optional<Pack> activeOn(Connection c, UUID legalEntityId, String code,
                                          LocalDate on) {
        List<Pack> found = query(c,
            "SELECT id, legal_entity_id, code, label, valid_from, valid_to FROM statement_pack"
            + " WHERE legal_entity_id = ? AND code = ? AND valid_from <= ?"
            + "   AND (valid_to IS NULL OR valid_to >= ?) ORDER BY valid_from DESC", ps -> {
                ps.setObject(1, legalEntityId);
                ps.setString(2, code);
                ps.setObject(3, on);
                ps.setObject(4, on);
            });
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    public static List<Pack> all(Connection c, UUID legalEntityId) {
        return query(c,
            "SELECT id, legal_entity_id, code, label, valid_from, valid_to FROM statement_pack"
            + " WHERE legal_entity_id = ? ORDER BY code, valid_from",
            ps -> ps.setObject(1, legalEntityId));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Pack> query(Connection c, String sql, Binder binder) {
        List<Pack> packs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = rs.getObject(1, UUID.class);
                    packs.add(new Pack(id, rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), items(c, id), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Liasses declarees", e);
        }
        return packs;
    }

    private static List<StatementLayouts.Kind> items(Connection c, UUID packId) {
        List<StatementLayouts.Kind> kinds = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind FROM statement_pack_item WHERE pack_id = ? ORDER BY ordinal")) {
            ps.setObject(1, packId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kinds.add(StatementLayouts.Kind.valueOf(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Composition de la liasse " + packId, e);
        }
        return kinds;
    }
}
