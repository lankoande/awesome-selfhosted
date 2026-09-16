package io.corebanking.loan.service;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Politique d'octroi d'un produit de credit : ce que la banque exige d'un dossier.
 *
 * <p>Elle ne refuse rien par elle-meme. Elle nomme les depassements, et c'est l'instruction qui
 * les porte au dossier : un credit hors politique reste decidable, mais la derogation doit etre
 * ecrite. Refuser automatiquement produirait deux effets connus — des dossiers montes juste sous
 * le seuil, et des derogations prises hors du systeme.
 *
 * <p>Tant qu'aucune politique n'est declaree pour un produit, rien n'est exige : la restriction
 * n'apparait qu'avec la regle, comme pour les plafonds, les suspens et la diligence client.
 */
public final class LendingPolicies {

    private LendingPolicies() {}

    /** Duree de validite d'une offre a defaut de politique declaree. */
    public static final int DEFAULT_VALIDITY_DAYS = 30;

    public record Policy(UUID id, UUID legalEntityId, String productCode,
                         BigDecimal maxDebtServiceRatioPercent, BigDecimal maxAmount,
                         Integer maxTermMonths, BigDecimal minDownPaymentPercent,
                         boolean collateralRequired, int decisionValidityDays,
                         LocalDate validFrom, LocalDate validTo,
                         UUID createdBy, UUID approvedBy) {}

    public record Draft(UUID legalEntityId, String productCode,
                        BigDecimal maxDebtServiceRatioPercent, BigDecimal maxAmount,
                        Integer maxTermMonths, BigDecimal minDownPaymentPercent,
                        boolean collateralRequired, Integer decisionValidityDays,
                        LocalDate validFrom, LocalDate validTo, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(productCode, "productCode");
            Objects.requireNonNull(validFrom, "validFrom");
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException(
                    "Une politique d'octroi ne cesse pas avant d'entrer en vigueur");
            }
            if (decisionValidityDays == null) {
                decisionValidityDays = DEFAULT_VALIDITY_DAYS;
            }
            if (decisionValidityDays <= 0) {
                throw new IllegalArgumentException("Une offre vaut au moins un jour : "
                                                   + decisionValidityDays);
            }
            if (maxTermMonths != null && maxTermMonths <= 0) {
                throw new IllegalArgumentException("La duree maximale se compte en mois : "
                                                   + maxTermMonths);
            }
            requirePercent(maxDebtServiceRatioPercent, "Le taux d'endettement maximal");
            requirePercent(minDownPaymentPercent, "L'apport minimal");
            if (maxAmount != null && maxAmount.signum() <= 0) {
                throw new IllegalArgumentException("Le montant maximal est positif : " + maxAmount);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une politique d'octroi se declare a deux : "
                    + "le demandeur ne peut pas etre le valideur");
            }
        }

        private static void requirePercent(BigDecimal value, String what) {
            if (value != null && (value.signum() < 0
                                  || value.compareTo(new BigDecimal("100")) > 0)) {
                throw new IllegalArgumentException(what + " est une part de 0 a 100 : " + value);
            }
        }
    }

    /** Declare la politique d'un produit. Les periodes ne se chevauchent pas : la base le tient. */
    public static Policy declare(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO lending_policy(id, legal_entity_id, product_code,"
            + " max_debt_service_ratio_percent, max_amount, max_term_months,"
            + " min_down_payment_percent, collateral_required, decision_validity_days,"
            + " valid_from, valid_to, created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.productCode());
            ps.setBigDecimal(4, draft.maxDebtServiceRatioPercent());
            ps.setBigDecimal(5, draft.maxAmount());
            if (draft.maxTermMonths() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, draft.maxTermMonths());
            }
            ps.setBigDecimal(7, draft.minDownPaymentPercent());
            ps.setBoolean(8, draft.collateralRequired());
            ps.setInt(9, draft.decisionValidityDays());
            ps.setObject(10, draft.validFrom());
            ps.setObject(11, draft.validTo());
            ps.setObject(12, draft.createdBy());
            ps.setObject(13, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23P01".equals(e.getSQLState())) {
                throw new IllegalStateException("Une politique d'octroi couvre deja le produit "
                    + draft.productCode() + " sur cette periode : la remplacer suppose de borner "
                    + "la precedente", e);
            }
            throw new LedgerStoreException("Declaration de la politique d'octroi", e);
        }
        return require(c, id);
    }

    /** Borne la politique en vigueur : ce qui la suit prend effet le lendemain. */
    public static void close(Connection c, UUID id, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE lending_policy SET valid_to = ? WHERE id = ? AND (valid_to IS NULL OR valid_to > ?)")) {
            ps.setObject(1, on);
            ps.setObject(2, id);
            ps.setObject(3, on);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("Politique d'octroi deja bornee : " + id);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Bornage de la politique d'octroi", e);
        }
    }

    public static Optional<Policy> find(Connection c, UUID legalEntityId, String productCode,
                                        LocalDate on) {
        return one(c, SELECT + " WHERE legal_entity_id = ? AND product_code = ?"
                        + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)",
                   ps -> {
                       ps.setObject(1, legalEntityId);
                       ps.setString(2, productCode);
                       ps.setObject(3, on);
                       ps.setObject(4, on);
                   });
    }

    public static Policy require(Connection c, UUID id) {
        return one(c, SELECT + " WHERE id = ?", ps -> ps.setObject(1, id))
            .orElseThrow(() -> new IllegalArgumentException("Politique d'octroi inconnue : " + id));
    }

    /** Les politiques d'une entite, produit par produit, la plus recente d'abord. */
    public static List<Policy> all(Connection c, UUID legalEntityId) {
        List<Policy> policies = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE legal_entity_id = ? ORDER BY product_code, valid_from DESC")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    policies.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des politiques d'octroi", e);
        }
        return policies;
    }

    private static final String SELECT =
        "SELECT id, legal_entity_id, product_code, max_debt_service_ratio_percent, max_amount,"
        + " max_term_months, min_down_payment_percent, collateral_required,"
        + " decision_validity_days, valid_from, valid_to, created_by, approved_by"
        + "  FROM lending_policy";

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Optional<Policy> one(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la politique d'octroi", e);
        }
    }

    private static Policy read(ResultSet rs) throws SQLException {
        Integer maxTerm = rs.getObject(6, Integer.class);
        return new Policy(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                          rs.getBigDecimal(4), rs.getBigDecimal(5), maxTerm, rs.getBigDecimal(7),
                          rs.getBoolean(8), rs.getInt(9), rs.getObject(10, LocalDate.class),
                          rs.getObject(11, LocalDate.class), rs.getObject(12, UUID.class),
                          rs.getObject(13, UUID.class));
    }
}
