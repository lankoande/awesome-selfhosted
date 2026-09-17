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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Le catalogue des taxes : ce que la banque preleve pour le compte de l'administration.
 *
 * <h2>Pourquoi un catalogue, alors que les taux sont deja quelque part</h2>
 *
 * <p>Les taux vivent la ou ils s'appliquent : la retenue a la source dans le parametrage des
 * interets, la taxe sur commission dans celui du produit. C'est juste — un taux se lit au moment
 * ou l'on calcule. Ce qui manquait, c'est <b>le compte de collecte</b> : la taxe prelevee
 * s'accumule quelque part jusqu'a son reversement, et sans un parametrage qui designe ce compte
 * comme tel, la declaration fiscale se fabrique a la main. Une declaration fabriquee a la main
 * est un redressement en puissance.
 *
 * <p><b>Un compte de collecte par taxe en vigueur.</b> Deux taxes qui partageraient le meme
 * compte rendraient la declaration ambigue : ce qui y est passe appartiendrait aux deux, et
 * serait declare deux fois. La base l'interdit par contrainte d'exclusion, et pas seulement le
 * code : c'est le genre d'erreur qu'une reprise de donnees introduit sans le dire.
 *
 * <p><b>Le taux du catalogue est le taux de reference</b>, celui qui se declare. Il ne remplace
 * pas celui qui a ete applique : une taxe prelevee hier au taux d'hier reste prelevee au taux
 * d'hier, et c'est le montant collecte — pas le taux — qui fait la declaration.
 */
public final class TaxRules {

    private TaxRules() {}

    /** Sur quoi la taxe porte. */
    public enum Basis {
        /** Retenue a la source sur les interets servis au client. */
        INTEREST_PAID,
        /** Taxe sur les commissions et frais factures. */
        FEES_CHARGED,
        /** Taxe sur les operations elles-memes. */
        TRANSACTION
    }

    public record Rule(UUID id, UUID legalEntityId, String code, String label, Basis basis,
                       BigDecimal ratePercent, UUID collectionAccountId, LocalDate validFrom,
                       LocalDate validTo) {}

    public record Draft(UUID legalEntityId, String code, String label, Basis basis,
                        BigDecimal ratePercent, UUID collectionAccountId, LocalDate validFrom,
                        LocalDate validTo, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(collectionAccountId, "collectionAccountId");
            Objects.requireNonNull(validFrom, "validFrom");
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Une taxe porte son code");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Une taxe porte son libelle : c'est lui que "
                    + "lira l'administration sur la declaration");
            }
            requireRate(ratePercent);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Une taxe ne cesse pas avant de commencer : "
                    + validFrom + " a " + validTo);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Un taux de taxe se pose a deux : il produit "
                    + "des montants sur des comptes clients, et engage la banque envers "
                    + "l'administration");
            }
        }
    }

    public static void requireRate(BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() < 0
            || ratePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Un taux de taxe va de 0 a 100 % : " + ratePercent);
        }
    }

    /** Taxe refusee : un parametrage qui rendrait la declaration ambigue ou fausse. */
    public static class TaxRuleRefusedException extends RuntimeException {
        public TaxRuleRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static UUID declare(Connection c, Draft draft) {
        requireCollectionAccount(c, draft.legalEntityId(), draft.collectionAccountId());
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO tax_rule(id, legal_entity_id, code, label, basis, rate_percent,"
            + " collection_account_id, valid_from, valid_to, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.label());
            ps.setString(5, draft.basis().name());
            ps.setBigDecimal(6, draft.ratePercent());
            ps.setObject(7, draft.collectionAccountId());
            ps.setObject(8, draft.validFrom());
            ps.setObject(9, draft.validTo());
            ps.setObject(10, draft.createdBy());
            ps.setObject(11, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                throw new TaxRuleRefusedException("La taxe " + draft.code() + " chevauche une "
                    + "version deja en vigueur, ou son compte de collecte sert deja a une autre "
                    + "taxe sur la meme periode : ce qui y passerait serait declare deux fois.",
                    e);
            }
            throw new LedgerStoreException("Declaration d'une taxe", e);
        }
        return id;
    }

    /**
     * Le compte de collecte existe, releve de l'entite, et est un compte general.
     *
     * <p>Le controle est ici et pas seulement a la frontiere : une taxe posee par une reprise de
     * donnees ou par un autre appelant serait declaree sur un compte d'une autre banque, ou sur
     * le compte d'un client — c'est-a-dire sur de l'argent qui n'appartient a personne.
     */
    private static void requireCollectionAccount(Connection c, UUID legalEntityId, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT legal_entity_id, account_kind, status FROM account WHERE id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new TaxRuleRefusedException("Compte de collecte inconnu : " + accountId,
                                                      null);
                }
                if (!legalEntityId.equals(rs.getObject(1, UUID.class))) {
                    throw new TaxRuleRefusedException("Le compte de collecte " + accountId
                        + " releve d'une autre entite : une taxe se collecte chez celui qui la "
                        + "preleve.", null);
                }
                if (!"GL".equals(rs.getString(2))) {
                    throw new TaxRuleRefusedException("Le compte de collecte d'une taxe est un "
                        + "compte general : collectee sur un compte client, la taxe serait de "
                        + "l'argent qui n'appartient a personne.", null);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Compte de collecte de la taxe", e);
        }
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, code, label, basis, rate_percent, collection_account_id,"
        + " valid_from, valid_to FROM tax_rule";

    public static List<Rule> activeOn(Connection c, UUID legalEntityId, LocalDate on) {
        return query(c, SELECT + " WHERE legal_entity_id = ? AND valid_from <= ?"
                        + "   AND (valid_to IS NULL OR valid_to >= ?) ORDER BY code", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, on);
            ps.setObject(3, on);
        });
    }

    public static List<Rule> all(Connection c, UUID legalEntityId) {
        return query(c, SELECT + " WHERE legal_entity_id = ? ORDER BY code, valid_from",
                     ps -> ps.setObject(1, legalEntityId));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Rule> query(Connection c, String sql, Binder binder) {
        List<Rule> rules = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rules.add(new Rule(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), Basis.valueOf(rs.getString(5)),
                        rs.getBigDecimal(6), rs.getObject(7, UUID.class),
                        rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Catalogue des taxes", e);
        }
        return rules;
    }
}
