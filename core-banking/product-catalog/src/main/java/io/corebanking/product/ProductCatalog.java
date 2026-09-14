package io.corebanking.product;

import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product factory : creation, activation et resolution du parametrage produit.
 *
 * <p>La resolution se fait toujours <b>a une date donnee</b>, et cette date est celle de la journee
 * traitee, jamais celle du traitement. C'est la difference entre un arrete rejouable et un arrete
 * dont le resultat depend du jour ou on le relance.
 */
public final class ProductCatalog {

    /** Noms de parametres attendus par le moteur d'interets. */
    public static final String P_RATE            = "interest.rate";
    public static final String P_DAY_COUNT       = "interest.day_count";
    public static final String P_SIDE            = "interest.side";
    public static final String P_TIERING_MODE    = "interest.tiering_mode";
    public static final String P_DEBIT_ACCOUNT   = "interest.debit_account";
    public static final String P_CREDIT_ACCOUNT  = "interest.credit_account";

    private ProductCatalog() {}

    /**
     * Version de produit soumise a validation.
     *
     * @param validTo borne incluse, nulle si sans terme
     */
    public record Draft(
        UUID legalEntityId,
        String code,
        String productType,
        String label,
        String currency,
        LocalDate validFrom,
        LocalDate validTo,
        Map<String, String> parameters,
        List<Tier> tiers,
        UUID createdBy) {}

    // ------------------------------------------------------------------ ecriture

    /** Cree une version a l'etat DRAFT. Elle n'est visible d'aucun traitement tant qu'elle l'est. */
    public static UUID createDraft(Connection c, Draft draft) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_version(id, legal_entity_id, code, product_type, label, currency,"
            + " valid_from, valid_to, status, created_by) VALUES (?,?,?,?,?,?,?,?,'DRAFT',?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.code());
            ps.setString(4, draft.productType());
            ps.setString(5, draft.label());
            ps.setString(6, draft.currency());
            ps.setObject(7, draft.validFrom());
            ps.setObject(8, draft.validTo());
            ps.setObject(9, draft.createdBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Creation de la version de produit " + draft.code(), e);
        }

        insertParameters(c, id, draft.parameters());
        insertTiers(c, id, draft.tiers());
        audit(c, id, "CREATE", draft.createdBy(),
              draft.code() + " du " + draft.validFrom()
              + (draft.validTo() == null ? " sans terme" : " au " + draft.validTo()));
        return id;
    }

    /**
     * Active une version. Le valideur ne peut pas etre le redacteur — la contrainte est portee par
     * la base, pas seulement par l'applicatif : un parametrage produit des montants sur des comptes
     * clients, il releve du meme regime de double validation qu'une operation.
     */
    public static void activate(Connection c, UUID versionId, UUID approverId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE product_version SET status = 'ACTIVE', approved_by = ?, approved_at = now()"
            + " WHERE id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, approverId);
            ps.setObject(2, versionId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Version " + versionId + " introuvable ou deja sortie de l'etat DRAFT.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Activation de la version " + versionId, e);
        }
        audit(c, versionId, "ACTIVATE", approverId, null);
    }

    /** Rattache un compte a un produit a compter d'une date. */
    public static void assignProduct(Connection c, UUID accountId, String productCode,
                                     LocalDate from, LocalDate to) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_product(account_id, product_code, valid_from, valid_to)"
            + " VALUES (?,?,?,?)")) {
            ps.setObject(1, accountId);
            ps.setString(2, productCode);
            ps.setObject(3, from);
            ps.setObject(4, to);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rattachement du compte " + accountId + " au produit "
                                           + productCode, e);
        }
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Version en vigueur a une date. Seules les versions ACTIVE sont visibles ; l'absence de
     * couverture est une erreur, jamais un repli silencieux.
     */
    public static ProductVersion resolveAt(Connection c, UUID legalEntityId, String code,
                                           LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, code, product_type, label, currency, valid_from, valid_to,"
            + " created_by, approved_by"
            + "  FROM product_version"
            + " WHERE legal_entity_id = ? AND code = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setObject(3, date);
            ps.setObject(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ProductNotFoundException(code, date);
                }
                UUID id = rs.getObject(1, UUID.class);
                ParameterSet parameters = new ParameterSet(code, loadParameters(c, id));
                RateSchedule tiers = loadTiers(c, id, parameters);
                return new ProductVersion(
                    id, rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                    rs.getObject(8, LocalDate.class), parameters, tiers,
                    rs.getObject(9, UUID.class), rs.getObject(10, UUID.class));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Resolution du produit " + code + " au " + date, e);
        }
    }

    /** Produit auquel un compte est rattache a une date, puis version en vigueur a cette date. */
    public static ProductVersion resolveForAccount(Connection c, UUID legalEntityId, UUID accountId,
                                                   LocalDate date) {
        String code;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT product_code FROM account_product"
            + " WHERE account_id = ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)"
            + " ORDER BY valid_from DESC LIMIT 1")) {
            ps.setObject(1, accountId);
            ps.setObject(2, date);
            ps.setObject(3, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ProductNotFoundException("rattache au compte " + accountId, date);
                }
                code = rs.getString(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Produit du compte " + accountId, e);
        }
        return resolveAt(c, legalEntityId, code, date);
    }

    // ------------------------------------------------------------------ interne

    private static void insertParameters(Connection c, UUID versionId, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_parameter(product_version_id, name, value) VALUES (?,?,?)")) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                ps.setObject(1, versionId);
                ps.setString(2, entry.getKey());
                ps.setString(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion des parametres produit", e);
        }
    }

    private static void insertTiers(Connection c, UUID versionId, List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }
        // Valide la contiguite des tranches des la saisie : un bareme lacunaire est refuse au
        // deploiement du parametrage, et non decouvert au milieu d'un TFJ.
        new TieredRate(tiers, TieringMode.PROGRESSIVE);

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_rate_tier(product_version_id, purpose, tier_order, from_amount,"
            + " to_amount, annual_rate_percent) VALUES (?,'INTEREST',?,?,?,?)")) {
            for (int i = 0; i < tiers.size(); i++) {
                Tier tier = tiers.get(i);
                ps.setObject(1, versionId);
                ps.setInt(2, i);
                ps.setBigDecimal(3, tier.from());
                ps.setBigDecimal(4, tier.to());
                ps.setBigDecimal(5, tier.annualRatePercent());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion du bareme", e);
        }
    }

    private static Map<String, String> loadParameters(Connection c, UUID versionId) {
        Map<String, String> parameters = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT name, value FROM product_parameter WHERE product_version_id = ? ORDER BY name")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parameters.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des parametres produit", e);
        }
        return parameters;
    }

    private static RateSchedule loadTiers(Connection c, UUID versionId, ParameterSet parameters) {
        List<Tier> tiers = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            // Un produit peut porter plusieurs baremes — interets, commissions. Celui-ci est
            // celui des interets ; les autres sont lus par le module qui les emploie.
            "SELECT from_amount, to_amount, annual_rate_percent FROM product_rate_tier"
            + " WHERE product_version_id = ? AND purpose = 'INTEREST' ORDER BY tier_order")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tiers.add(new Tier(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du bareme", e);
        }
        if (tiers.isEmpty()) {
            return null;
        }
        TieringMode mode = TieringMode.valueOf(
            parameters.optionalString(P_TIERING_MODE, TieringMode.PROGRESSIVE.name()));
        return new TieredRate(tiers, mode);
    }

    private static void audit(Connection c, UUID versionId, String action, UUID actorId,
                              String detail) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_audit(product_version_id, action, actor_id, detail)"
            + " VALUES (?,?,?,?)")) {
            ps.setObject(1, versionId);
            ps.setString(2, action);
            ps.setObject(3, actorId);
            ps.setString(4, detail);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Journalisation du parametrage", e);
        }
    }
}
