package io.corebanking.loan.service;

import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.CollateralCharge;
import io.corebanking.loan.CollateralPolicy;
import io.corebanking.loan.CollateralValuation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Depot des suretes, de leur regime d'eligibilite et de leur affectation aux credits. */
public final class Collaterals {

    private Collaterals() {}

    // ------------------------------------------------------------------ regime

    public record PolicyDraft(UUID legalEntityId, CollateralPolicy policy, LocalDate validFrom,
                              LocalDate validTo, UUID createdBy) {}

    public static UUID createPolicy(Connection c, PolicyDraft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO collateral_policy(id, legal_entity_id, kind, label,"
            + " eligible_rate_percent, max_valuation_age_months, valid_from, valid_to, status,"
            + " created_by) VALUES (?,?,?,?,?,?,?,?,'DRAFT',?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.policy().kind());
            ps.setString(4, draft.policy().label());
            ps.setBigDecimal(5, draft.policy().eligibleRatePercent());
            ps.setInt(6, draft.policy().maxValuationAgeMonths());
            ps.setObject(7, draft.validFrom());
            ps.setObject(8, draft.validTo());
            ps.setObject(9, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation du regime de surete "
                                           + draft.policy().kind(), e);
        }
        return id;
    }

    public static void activatePolicy(Connection c, UUID policyId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE collateral_policy SET status = 'ACTIVE', approved_by = ?"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, policyId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Regime de surete " + policyId + " introuvable ou deja sorti de DRAFT.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation du regime de surete " + policyId, e);
        }
    }

    /**
     * Regimes en vigueur a une date, par type de surete.
     *
     * <p>Un type absent de la table n'a pas de regime, et sa surete sera ecartee. C'est voulu :
     * retenir a cent pour cent un type dont personne n'a fixe la decote serait exactement l'erreur
     * que la quotite est faite d'empecher.
     */
    public static Map<String, CollateralPolicy> policiesAt(Connection c, UUID legalEntityId,
                                                           LocalDate date) {
        Map<String, CollateralPolicy> policies = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT kind, label, eligible_rate_percent, max_valuation_age_months"
            + "  FROM collateral_policy"
            + " WHERE legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, date);
            ps.setObject(3, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    policies.put(rs.getString(1), new CollateralPolicy(
                        rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des regimes de surete", e);
        }
        return policies;
    }

    // ------------------------------------------------------------------ suretes

    public record Draft(UUID legalEntityId, UUID customerId, String assetReference, String kind,
                        String label, Money assetValue, Money securedAmount, int rank,
                        LocalDate valuedOn, UUID createdBy, UUID approvedBy) {}

    public static UUID register(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO collateral(id, legal_entity_id, customer_id, asset_reference, kind,"
            + " label, asset_value, secured_amount, rank, valued_on, created_by, approved_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setObject(3, draft.customerId());
            ps.setString(4, draft.assetReference());
            ps.setString(5, draft.kind());
            ps.setString(6, draft.label());
            ps.setBigDecimal(7, draft.assetValue().amount());
            ps.setBigDecimal(8, draft.securedAmount().amount());
            ps.setInt(9, draft.rank());
            ps.setObject(10, draft.valuedOn());
            ps.setObject(11, draft.createdBy());
            ps.setObject(12, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de la surete " + draft.label(), e);
        }
        return id;
    }

    /** Affecte une quote-part de la surete a un credit. */
    public static void allocate(Connection c, UUID collateralId, UUID contractId,
                                BigDecimal sharePercent) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO collateral_allocation(collateral_id, contract_id, share_percent)"
            + " VALUES (?,?,?)")) {
            ps.setObject(1, collateralId);
            ps.setObject(2, contractId);
            ps.setBigDecimal(3, sharePercent);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Affectation de la surete " + collateralId, e);
        }
    }

    /**
     * Mainlevee : la surete cesse de couvrir quoi que ce soit.
     *
     * <p>Le rang qu'elle occupait se libere pour les suretes qui la suivent — c'est pourquoi elle
     * est marquee plutot que supprimee : l'historique des rangs est une piece du dossier.
     */
    public static void release(Connection c, UUID collateralId, LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE collateral SET status = 'RELEASED', released_on = ?"
            + " WHERE id = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, on);
            ps.setObject(2, collateralId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Mainlevee de la surete " + collateralId, e);
        }
    }

    // ------------------------------------------------------------------ valorisation

    /**
     * Suretes d'un credit, chacune accompagnee de ce que les rangs anterieurs mobilisent sur le
     * meme actif.
     *
     * <p>Les rangs anterieurs sont comptes <b>toutes affectations confondues</b>, y compris au
     * profit d'un autre credit ou d'une autre banque : ce qui compte est ce qui reste de l'actif,
     * pas ce que la banque en a deja pris pour elle.
     */
    public static List<CollateralValuation.Charged> chargesOf(Connection c, UUID legalEntityId,
                                                              UUID contractId,
                                                              CurrencyRef currency) {
        List<CollateralValuation.Charged> charges = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT g.id, g.asset_reference, g.kind, g.asset_value, g.secured_amount, g.rank,"
            + "       g.valued_on, a.share_percent,"
            + "       COALESCE((SELECT SUM(s.secured_amount) FROM collateral s"
            + "                  WHERE s.legal_entity_id = g.legal_entity_id"
            + "                    AND s.asset_reference = g.asset_reference"
            + "                    AND s.status = 'ACTIVE' AND s.rank < g.rank), 0)"
            + "  FROM collateral g"
            + "  JOIN collateral_allocation a ON a.collateral_id = g.id"
            + " WHERE a.contract_id = ? AND g.legal_entity_id = ? AND g.status = 'ACTIVE'"
            + " ORDER BY g.asset_reference, g.rank")) {
            ps.setObject(1, contractId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    charges.add(new CollateralValuation.Charged(
                        new CollateralCharge(
                            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            Money.of(rs.getBigDecimal(4), currency),
                            Money.of(rs.getBigDecimal(5), currency), rs.getInt(6),
                            rs.getObject(7, LocalDate.class), rs.getBigDecimal(8)),
                        Money.of(rs.getBigDecimal(9), currency)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des suretes du contrat " + contractId, e);
        }
        return charges;
    }

    // ------------------------------------------------------------------ en-tetes

    /** Ce qu'il faut savoir d'un regime avant de decider de son activation. */
    public record PolicyHeader(UUID id, UUID legalEntityId, String kind, String status,
                               UUID createdBy) {}

    public static Optional<PolicyHeader> findPolicy(Connection c, UUID policyId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, kind, status, created_by FROM collateral_policy"
            + " WHERE id = ?")) {
            ps.setObject(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new PolicyHeader(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getObject(5, UUID.class))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du regime de surete " + policyId, e);
        }
    }

    /** L'en-tete d'une surete : son entite et son etat. */
    public record Header(UUID id, UUID legalEntityId, String assetReference, String kind,
                         String status) {}

    public static Optional<Header> find(Connection c, UUID collateralId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, asset_reference, kind, status FROM collateral"
            + " WHERE id = ?")) {
            ps.setObject(1, collateralId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Header(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getString(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la surete " + collateralId, e);
        }
    }
}
