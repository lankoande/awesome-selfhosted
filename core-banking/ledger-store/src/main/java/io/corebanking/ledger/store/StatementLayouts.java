package io.corebanking.ledger.store;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.Direction;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Maquettes d'etats financiers : bilan, compte de resultat, hors bilan.
 *
 * <p>Une maquette est un parametrage : des rubriques ordonnees — de detail, de total, ou la
 * rubrique du resultat de l'exercice — et des regles qui affectent chaque compte a une rubrique
 * de detail selon sa nature de compte, le prefixe de son code et le sens de son solde. Les
 * regles se lisent dans l'ordre, la premiere qui reconnait le compte l'emporte : c'est l'auteur
 * qui ecrit la precedence, pas le hasard d'un chevauchement. Une maquette se redige, puis
 * s'active a deux ; elle est verifiee avant d'entrer en base, comme un schema comptable.
 */
public final class StatementLayouts {

    private StatementLayouts() {}

    /** Nature d'etat, et la nature des comptes qu'il presente. */
    public enum Kind {
        BALANCE_SHEET(AccountNature.BALANCE_SHEET),
        INCOME_STATEMENT(AccountNature.PROFIT_AND_LOSS),
        OFF_BALANCE_SHEET(AccountNature.OFF_BALANCE_SHEET);

        private final AccountNature nature;

        Kind(AccountNature nature) {
            this.nature = nature;
        }

        public AccountNature nature() {
            return nature;
        }
    }

    /**
     * Une rubrique de detail recoit des comptes ; une rubrique de total somme des rubriques qui
     * la precedent ; la rubrique du resultat de l'exercice, propre au bilan, recoit le resultat
     * de l'exercice en cours, calcule par le socle et jamais par une regle.
     */
    public enum LineKind { DETAIL, TOTAL, PROFIT_OR_LOSS }

    /**
     * @param side  sens de presentation : le montant est positif quand le solde est de ce cote
     * @param plus  rubriques ajoutees par un total
     * @param minus rubriques retranchees par un total
     */
    public record Line(int ordinal, String code, String label, int level, LineKind kind,
                       Direction side, List<String> plus, List<String> minus) {
        public Line {
            plus = plus == null ? List.of() : List.copyOf(plus);
            minus = minus == null ? List.of() : List.copyOf(minus);
        }
    }

    /** Une regle d'affectation : tout critere absent est indifferent ; il en faut au moins un. */
    public record Rule(int ordinal, String lineCode, AccountKind accountKind, String codePrefix,
                       Direction balanceSide) {

        public boolean matches(String code, AccountKind kind, Direction side) {
            return (accountKind == null || accountKind == kind)
                   && (codePrefix == null || code.startsWith(codePrefix))
                   && (balanceSide == null || balanceSide == side);
        }
    }

    public record Layout(UUID id, UUID legalEntityId, Kind kind, String code, String label,
                         LocalDate validFrom, LocalDate validTo, String status, UUID createdBy,
                         UUID approvedBy, List<Line> lines, List<Rule> rules) {

        public Optional<Line> line(String lineCode) {
            return lines.stream().filter(line -> line.code().equals(lineCode)).findFirst();
        }
    }

    public record Draft(UUID legalEntityId, Kind kind, String code, String label,
                        LocalDate validFrom, LocalDate validTo, List<Line> lines,
                        List<Rule> rules, UUID createdBy) {
        public Draft {
            lines = lines == null ? List.of() : List.copyOf(lines);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    // ------------------------------------------------------------------ validation

    /** Verifie une maquette avant qu'elle n'entre en base : une maquette fausse n'y dort jamais. */
    public static void validate(Draft draft) {
        require(draft.legalEntityId() != null, "L'entite est obligatoire");
        require(draft.kind() != null, "La nature de l'etat est obligatoire");
        require(notBlank(draft.code()) && notBlank(draft.label()),
                "Une maquette porte un code et un libelle");
        require(draft.validFrom() != null, "Le debut de validite est obligatoire");
        require(draft.validTo() == null || !draft.validTo().isBefore(draft.validFrom()),
                "La fin de validite precede son debut");
        require(draft.createdBy() != null, "Le redacteur est obligatoire");
        require(!draft.lines().isEmpty(), "Une maquette a au moins une rubrique");

        Map<String, Line> byCode = new LinkedHashMap<>();
        Set<Integer> ordinals = new HashSet<>();
        boolean detail = false;
        boolean result = false;
        for (Line line : draft.lines()) {
            require(ordinals.add(line.ordinal()), "Deux rubriques portent le rang " + line.ordinal());
            require(notBlank(line.code()) && notBlank(line.label()),
                    "La rubrique de rang " + line.ordinal() + " porte un code et un libelle");
            require(byCode.put(line.code(), line) == null,
                    "Deux rubriques portent le code " + line.code());
            require(line.level() >= 0, "Rubrique " + line.code() + " : niveau negatif");
            require(line.kind() != null && line.side() != null,
                    "Rubrique " + line.code() + " : nature et sens obligatoires");
            switch (line.kind()) {
                case DETAIL -> {
                    require(line.plus().isEmpty() && line.minus().isEmpty(),
                            "Rubrique " + line.code() + " : une rubrique de detail ne totalise rien");
                    detail = true;
                }
                case TOTAL -> {
                    require(!line.plus().isEmpty() || !line.minus().isEmpty(),
                            "Rubrique " + line.code() + " : un total somme au moins une rubrique");
                    for (String referenced : concat(line.plus(), line.minus())) {
                        Line target = byCode.get(referenced);
                        require(target != null && target.ordinal() < line.ordinal(),
                                "Rubrique " + line.code() + " : totalise " + referenced
                                + ", qui la suit ou n'existe pas");
                    }
                }
                case PROFIT_OR_LOSS -> {
                    require(draft.kind() == Kind.BALANCE_SHEET,
                            "Rubrique " + line.code() + " : le resultat de l'exercice ne se "
                            + "presente qu'au bilan");
                    require(line.side() == Direction.CREDIT,
                            "Rubrique " + line.code() + " : le resultat se presente au credit, "
                            + "positif pour un benefice");
                    require(line.plus().isEmpty() && line.minus().isEmpty(),
                            "Rubrique " + line.code() + " : le resultat ne totalise rien");
                    require(!result, "Une seule rubrique presente le resultat de l'exercice");
                    result = true;
                }
            }
        }
        require(detail, "Une maquette a au moins une rubrique de detail");
        require(!draft.rules().isEmpty(),
                "Une maquette sans regle ne presenterait aucun compte");
        Set<Integer> ruleOrdinals = new HashSet<>();
        for (Rule rule : draft.rules()) {
            require(ruleOrdinals.add(rule.ordinal()),
                    "Deux regles portent le rang " + rule.ordinal());
            Line target = rule.lineCode() == null ? null : byCode.get(rule.lineCode());
            require(target != null && target.kind() == LineKind.DETAIL,
                    "Regle de rang " + rule.ordinal() + " : une regle affecte a une rubrique de "
                    + "detail existante, pas a " + rule.lineCode());
            require(rule.accountKind() != null || rule.codePrefix() != null
                    || rule.balanceSide() != null,
                    "Regle de rang " + rule.ordinal() + " : au moins un critere");
            require(rule.codePrefix() == null || !rule.codePrefix().isBlank(),
                    "Regle de rang " + rule.ordinal() + " : prefixe vide");
        }
    }

    // ------------------------------------------------------------------ ecriture

    public static UUID createDraft(Connection c, Draft draft) {
        validate(draft);
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO statement_layout(id, legal_entity_id, kind, code, label, valid_from,"
            + " valid_to, created_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.kind().name());
            ps.setString(4, draft.code().trim());
            ps.setString(5, draft.label().trim());
            ps.setObject(6, draft.validFrom());
            ps.setObject(7, draft.validTo());
            ps.setObject(8, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation de la maquette " + draft.code(), e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO statement_line(layout_id, ordinal, code, label, level, kind, side,"
            + " plus, minus) VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (Line line : draft.lines()) {
                ps.setObject(1, id);
                ps.setInt(2, line.ordinal());
                ps.setString(3, line.code());
                ps.setString(4, line.label());
                ps.setInt(5, line.level());
                ps.setString(6, line.kind().name());
                ps.setString(7, line.side().name());
                ps.setArray(8, c.createArrayOf("text", line.plus().toArray()));
                ps.setArray(9, c.createArrayOf("text", line.minus().toArray()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rubriques de la maquette " + draft.code(), e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO statement_rule(layout_id, ordinal, line_code, account_kind, code_prefix,"
            + " balance_side) VALUES (?,?,?,?,?,?)")) {
            for (Rule rule : draft.rules()) {
                ps.setObject(1, id);
                ps.setInt(2, rule.ordinal());
                ps.setString(3, rule.lineCode());
                ps.setString(4, rule.accountKind() == null ? null : rule.accountKind().name());
                ps.setString(5, rule.codePrefix());
                ps.setString(6, rule.balanceSide() == null ? null : rule.balanceSide().name());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Regles de la maquette " + draft.code(), e);
        }
        return id;
    }

    /** Active une maquette : par un autre que son redacteur, et une seule par nature et par date. */
    public static void activate(Connection c, UUID layoutId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE statement_layout SET status = 'ACTIVE', approved_by = ?, approved_at = now()"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, layoutId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Maquette " + layoutId + " introuvable ou deja sortie de l'etat DRAFT.");
            }
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                // exclusion_violation : une autre maquette de cette nature est active sur la
                // periode. Un conflit d'etat, pas une panne.
                throw new IllegalStateException(
                    "Une autre maquette de cette nature d'etat est active sur la periode de "
                    + "validite de la maquette " + layoutId + " : une seule a la fois, la "
                    + "precedente se termine avant que la suivante ne commence", e);
            }
            throw new LedgerStoreException(
                "Activation de la maquette " + layoutId + " (par un autre que son redacteur)", e);
        }
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT id, legal_entity_id, kind, code, label, valid_from, valid_to, status, created_by,"
        + " approved_by FROM statement_layout";

    /** La maquette active d'une nature d'etat a une date. Une date non couverte est une erreur. */
    public static Layout resolveAt(Connection c, UUID legalEntityId, Kind kind, LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? AND kind = ? AND status = 'ACTIVE'"
            + " AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, kind.name());
            ps.setObject(3, date);
            ps.setObject(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new NoLayoutException(kind, date);
                }
                return complete(c, header(rs));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche de la maquette " + kind + " au " + date, e);
        }
    }

    public static Optional<Layout> find(Connection c, UUID layoutId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setObject(1, layoutId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(complete(c, header(rs))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la maquette " + layoutId, e);
        }
    }

    private static Layout header(ResultSet rs) throws SQLException {
        return new Layout(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                          Kind.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5),
                          rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
                          rs.getString(8), rs.getObject(9, UUID.class),
                          rs.getObject(10, UUID.class), List.of(), List.of());
    }

    private static Layout complete(Connection c, Layout header) throws SQLException {
        List<Line> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT ordinal, code, label, level, kind, side, plus, minus FROM statement_line"
            + " WHERE layout_id = ? ORDER BY ordinal")) {
            ps.setObject(1, header.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new Line(rs.getInt(1), rs.getString(2), rs.getString(3),
                                       rs.getInt(4), LineKind.valueOf(rs.getString(5)),
                                       Direction.valueOf(rs.getString(6)),
                                       strings(rs.getArray(7)), strings(rs.getArray(8))));
                }
            }
        }
        List<Rule> rules = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT ordinal, line_code, account_kind, code_prefix, balance_side"
            + " FROM statement_rule WHERE layout_id = ? ORDER BY ordinal")) {
            ps.setObject(1, header.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(3);
                    String side = rs.getString(5);
                    rules.add(new Rule(rs.getInt(1), rs.getString(2),
                                       kind == null ? null : AccountKind.valueOf(kind),
                                       rs.getString(4),
                                       side == null ? null : Direction.valueOf(side)));
                }
            }
        }
        return new Layout(header.id(), header.legalEntityId(), header.kind(), header.code(),
                          header.label(), header.validFrom(), header.validTo(), header.status(),
                          header.createdBy(), header.approvedBy(), lines, rules);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    // ------------------------------------------------------------------ interne

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new InvalidLayoutException(detail);
        }
    }

    /** Une maquette qui ne se tient pas : une requete fausse, refusee avant d'entrer en base. */
    public static class InvalidLayoutException extends IllegalArgumentException {
        public InvalidLayoutException(String detail) {
            super("Maquette invalide : " + detail);
        }
    }

    /** Aucune maquette active a la date : un etat ne se produit pas sans maquette, et ne se rabat sur rien. */
    public static class NoLayoutException extends IllegalStateException {
        public NoLayoutException(Kind kind, LocalDate date) {
            super("Aucune maquette de " + kind + " active au " + date
                  + " : rediger et activer une maquette, l'etat ne se rabat sur rien");
        }
    }
}
