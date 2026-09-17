package io.corebanking.regulatory;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Le catalogue des declarations : ce que la banque doit au superviseur, et quand.
 *
 * <p><b>La declaration est du parametrage, la methode est du code.</b> Les destinataires, les
 * periodicites, les delais et les seuils changent par circulaire — parfois deux fois dans
 * l'annee. Les coder obligerait a livrer pour deplacer un seuil, et une banque qui attend la
 * prochaine version est en retard declaratif. Ce qui reste du code, c'est <b>ce qu'on sait
 * produire</b> : une situation comptable, un recensement d'engagements, un releve d'incidents,
 * un historique de remboursement. Ajouter une methode est une livraison, et c'est normal — elle
 * change ce que la banque sait declarer.
 *
 * <p><b>Elle se declare a deux.</b> Une declaration engage la banque devant son superviseur ;
 * l'oublier, la mal dater ou la sous-seuiller sont trois facons de manquer a la meme obligation,
 * et aucune ne se decide seul.
 */
public final class RegulatoryDeclarations {

    private RegulatoryDeclarations() {}

    /** A qui l'etat est adresse. Le destinataire commande le secret et le circuit. */
    public enum Recipient { CENTRAL_BANK, BANKING_COMMISSION, CREDIT_BUREAU, TAX_AUTHORITY }

    /** Ce que le code sait produire. */
    public enum Method {
        /** Balance des comptes generaux a la fin de periode, en devise de tenue. */
        ACCOUNTING_SITUATION,
        /** Engagements recenses par client, bilan et hors bilan, au-dela d'un seuil. */
        CREDIT_REGISTRY,
        /** Incidents de paiement de la periode : cheques impayes. */
        PAYMENT_INCIDENTS,
        /** Historique de remboursement des clients qui y ont consenti. */
        CREDIT_BUREAU,
        /** Taxes collectees sur la periode, par taxe, lues sur leur compte de collecte. */
        TAX_COLLECTION,
        /** Une liasse : les etats declares comme un tout, et confrontes entre eux. */
        STATEMENT_PACK,
        /** Les comptes du groupe : agregation par entite, quote-part, eliminations. */
        CONSOLIDATED_STATEMENTS
    }

    public enum Frequency {
        MONTHLY, QUARTERLY, YEARLY;

        /** La periode close par cette date de fin, si c'en est une. */
        public Optional<LocalDate> startOfPeriodEndingOn(LocalDate periodEnd) {
            LocalDate start = switch (this) {
                case MONTHLY -> periodEnd.withDayOfMonth(1);
                case QUARTERLY -> periodEnd.withDayOfMonth(1)
                    .minusMonths((periodEnd.getMonthValue() - 1) % 3);
                case YEARLY -> periodEnd.withDayOfYear(1);
            };
            return lastDayOf(start).equals(periodEnd) ? Optional.of(start) : Optional.empty();
        }

        /** La fin de la periode qui contient cette date. */
        public LocalDate endOfPeriodContaining(LocalDate date) {
            return lastDayOf(startOfPeriodContaining(date));
        }

        public LocalDate startOfPeriodContaining(LocalDate date) {
            return switch (this) {
                case MONTHLY -> date.withDayOfMonth(1);
                case QUARTERLY -> date.withDayOfMonth(1)
                    .minusMonths((date.getMonthValue() - 1) % 3);
                case YEARLY -> date.withDayOfYear(1);
            };
        }

        /** La periode qui suit celle qui commence ici. */
        public LocalDate nextStart(LocalDate start) {
            return switch (this) {
                case MONTHLY -> start.plusMonths(1);
                case QUARTERLY -> start.plusMonths(3);
                case YEARLY -> start.plusYears(1);
            };
        }

        private LocalDate lastDayOf(LocalDate start) {
            return nextStart(start).minusDays(1);
        }
    }

    /**
     * @param subjectCode ce que la declaration produit quand la methode vise un objet nomme —
     *     une liasse, un perimetre de consolidation ; nul pour les methodes qui portent sur
     *     toute l'entite
     */
    public record Declaration(UUID id, UUID legalEntityId, String code, String label,
                              Recipient recipient, Method method, Frequency frequency,
                              int deadlineDays, BigDecimal thresholdAmount, String subjectCode,
                              LocalDate validFrom, LocalDate validTo) {

        /** L'echeance de transmission de la periode qui se termine ce jour-la. */
        public LocalDate dueOn(LocalDate periodEnd) {
            return periodEnd.plusDays(deadlineDays);
        }
    }

    public record Draft(UUID legalEntityId, String code, String label, Recipient recipient,
                        Method method, Frequency frequency, Integer deadlineDays,
                        BigDecimal thresholdAmount, String subjectCode, LocalDate validFrom,
                        LocalDate validTo, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(validFrom, "validFrom");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Une declaration porte son code");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Une declaration porte son libelle : c'est ce "
                    + "que lira celui qui la transmet");
            }
            requireDeadline(deadlineDays);
            requireThreshold(method, thresholdAmount);
            requireSubject(method, subjectCode);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Une declaration ne cesse pas avant de "
                    + "commencer : " + validFrom + " a " + validTo);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une declaration reglementaire se decide a "
                    + "deux : elle engage la banque devant son superviseur");
            }
        }
    }

    /** Delai maximal admis : au-dela d'un an, ce n'est plus un delai, c'est un oubli. */
    public static final int MAX_DEADLINE_DAYS = 365;

    public static void requireDeadline(Integer deadlineDays) {
        if (deadlineDays == null || deadlineDays < 1 || deadlineDays > MAX_DEADLINE_DAYS) {
            throw new IllegalArgumentException("Un delai de transmission va de 1 a "
                + MAX_DEADLINE_DAYS + " jours apres la fin de periode : " + deadlineDays);
        }
    }

    /**
     * Le seuil n'a de sens que pour ce qui se recense au-dela d'un montant.
     *
     * <p>Une situation comptable seuil<b>ee</b> serait fausse : la balance est exhaustive ou elle
     * n'est pas une balance. Un incident de paiement se declare quel que soit son montant : c'est
     * l'incident qui compte, pas la somme.
     */
    /**
     * Les methodes qui visent un objet nomme le disent, les autres n'ont rien a viser.
     *
     * <p>Une liasse ou un perimetre porte un code : sans lui, la declaration ne saurait pas
     * lequel produire. Une situation comptable, elle, porte sur toute l'entite — lui donner un
     * sujet laisserait croire qu'elle n'en couvre qu'une partie.
     */
    public static void requireSubject(Method method, String subjectCode) {
        boolean needed = method == Method.STATEMENT_PACK
                         || method == Method.CONSOLIDATED_STATEMENTS;
        boolean given = subjectCode != null && !subjectCode.isBlank();
        if (needed && !given) {
            throw new IllegalArgumentException("La methode " + method + " produit un objet nomme : "
                + "la declaration dit lequel (le code de la liasse ou du perimetre)");
        }
        if (!needed && given) {
            throw new IllegalArgumentException("La methode " + method + " porte sur toute "
                + "l'entite : lui donner un sujet laisserait croire qu'elle n'en couvre qu'une "
                + "partie");
        }
    }

    public static void requireThreshold(Method method, BigDecimal thresholdAmount) {
        boolean admits = method == Method.CREDIT_REGISTRY;
        // Une declaration fiscale porte ce qui a ete collecte, a l'unite pres : la seuiller
        // reviendrait a garder une part de la taxe des clients sans la reverser.
        if (thresholdAmount != null && !admits) {
            throw new IllegalArgumentException("La methode " + method + " ne se seuille pas : "
                + "une situation comptable est exhaustive, un incident de paiement se declare "
                + "quel que soit son montant, et une taxe collectee se reverse en entier");
        }
        if (thresholdAmount != null && thresholdAmount.signum() <= 0) {
            throw new IllegalArgumentException("Un seuil de declaration est positif : "
                                               + thresholdAmount);
        }
    }

    public static UUID declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO regulatory_declaration(id, legal_entity_id, code, label, recipient,"
            + " method, frequency, deadline_days, threshold_amount, subject_code, valid_from,"
            + " valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setString(5, draft.recipient().name());
            ps.setString(6, draft.method().name());
            ps.setString(7, draft.frequency().name());
            ps.setInt(8, draft.deadlineDays());
            if (draft.thresholdAmount() == null) {
                ps.setNull(9, Types.NUMERIC);
            } else {
                ps.setBigDecimal(9, draft.thresholdAmount());
            }
            ps.setString(10, draft.subjectCode());
            ps.setObject(11, draft.validFrom());
            ps.setObject(12, draft.validTo());
            ps.setObject(13, draft.createdBy());
            ps.setObject(14, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Une declaration " + draft.code()
                    + " est deja en vigueur au " + draft.validFrom(), e);
            }
            throw new LedgerStoreException("Declaration reglementaire", e);
        }
        return id;
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, code, label, recipient, method, frequency, deadline_days,"
        + " threshold_amount, subject_code, valid_from, valid_to FROM regulatory_declaration";

    public static List<Declaration> activeOn(Connection c, UUID legalEntityId, LocalDate on) {
        return query(c, SELECT + " WHERE legal_entity_id = ? AND valid_from <= ?"
                        + "   AND (valid_to IS NULL OR valid_to >= ?)"
                        + " ORDER BY code, valid_from", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, on);
            ps.setObject(3, on);
        });
    }

    public static List<Declaration> all(Connection c, UUID legalEntityId) {
        return query(c, SELECT + " WHERE legal_entity_id = ? ORDER BY code, valid_from",
                     ps -> ps.setObject(1, legalEntityId));
    }

    public static Declaration require(Connection c, UUID declarationId) {
        List<Declaration> found = query(c, SELECT + " WHERE id = ?",
                                        ps -> ps.setObject(1, declarationId));
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Declaration inconnue : " + declarationId);
        }
        return found.getFirst();
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Declaration> query(Connection c, String sql, Binder binder) {
        List<Declaration> declarations = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    declarations.add(new Declaration(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        Recipient.valueOf(rs.getString(5)), Method.valueOf(rs.getString(6)),
                        Frequency.valueOf(rs.getString(7)), rs.getInt(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getObject(11, LocalDate.class),
                        rs.getObject(12, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Declarations reglementaires", e);
        }
        return declarations;
    }
}
