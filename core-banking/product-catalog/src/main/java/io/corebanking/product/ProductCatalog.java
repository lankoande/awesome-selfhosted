package io.corebanking.product;

import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** Periodicite civile de capitalisation des interets courus au client. */
    public static final String P_CAPITALISATION  = "interest.capitalisation";
    /** Code de la retenue a la source appliquee aux interets crediteurs ; absent : exonere. */
    public static final String P_WITHHOLDING     = "interest.withholding";

    // Agios : le cote debiteur d'un compte courant, facultatif.
    public static final String P_OD_LIMIT          = "overdraft.limit";
    public static final String P_OD_RATE           = "overdraft.rate";
    public static final String P_OD_EXCESS_RATE    = "overdraft.excess_rate";
    public static final String P_OD_DAY_COUNT      = "overdraft.day_count";
    public static final String P_OD_DEBIT_ACCOUNT  = "overdraft.debit_account";
    public static final String P_OD_CREDIT_ACCOUNT = "overdraft.credit_account";
    public static final String P_OD_SETTLEMENT     = "overdraft.settlement";
    public static final String P_OD_TAX_RATE       = "overdraft.tax_rate";
    public static final String P_OD_TAX_ACCOUNT    = "overdraft.tax_account";

    /** Discriminant du bareme des interets ; les commissions portent {@code FEE:<code>}. */
    public static final String PURPOSE_INTEREST = "INTEREST";

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
        Map<String, List<Tier>> feeTiers,
        UUID createdBy) {

        public Draft {
            feeTiers = feeTiers == null ? Map.of() : Map.copyOf(feeTiers);
        }

        /** Une version sans bareme de commission : le cas courant. */
        public Draft(UUID legalEntityId, String code, String productType, String label,
                     String currency, LocalDate validFrom, LocalDate validTo,
                     Map<String, String> parameters, List<Tier> tiers, UUID createdBy) {
            this(legalEntityId, code, productType, label, currency, validFrom, validTo,
                 parameters, tiers, Map.of(), createdBy);
        }
    }

    // ------------------------------------------------------------------ ecriture

    /**
     * Cree une version a l'etat DRAFT. Elle n'est visible d'aucun traitement tant qu'elle l'est.
     *
     * <p>Seul le type de produit est controle ici : il designe la famille, donc le contrat de
     * parametrage a respecter. Le refuser des la saisie evite de decouvrir la faute de frappe apres
     * avoir renseigne trente parametres. Leur completude, elle, est verifiee a l'activation — un
     * brouillon a le droit d'etre incomplet, c'est ce qui en fait un brouillon.
     */
    public static UUID createDraft(Connection c, Draft draft) {
        ProductFamilies.require(draft.productType());
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
        insertTiers(c, id, PURPOSE_INTEREST, draft.tiers());
        for (Map.Entry<String, List<Tier>> schedule : draft.feeTiers().entrySet()) {
            insertTiers(c, id, schedule.getKey(), schedule.getValue());
        }
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
    /**
     * Active une version apres controle de completude.
     *
     * <p>C'est <b>ici</b> que le parametrage est confronte a sa famille, et nulle part ailleurs.
     * Un parametre manquant decouvert au traitement de fin de journee coute une nuit d'exploitation
     * et un arrete a reprendre ; decouvert ici, il coute une ligne a ajouter, devant celui qui sait
     * quoi y mettre.
     *
     * <p>Le controle vient <b>avant</b> la double validation, et non apres : faire valider par un
     * second regard un parametrage que la machine sait incomplet lui ferait porter une
     * responsabilite sur une piece incomplete.
     */
    /**
     * Ce qui est ouvrable a une date : les versions actives dont la validite couvre ce jour.
     *
     * <p>Un produit peut avoir plusieurs versions ; celle qui compte est celle en vigueur. On ne
     * rend donc <b>qu'une ligne par code</b>, la plus recemment entree en vigueur — proposer deux
     * fois le meme produit avec deux parametrages ferait choisir au guichet ce qui ne se choisit
     * pas la.
     *
     * <p>Les versions {@code DRAFT}, {@code SUSPENDED} et {@code WITHDRAWN} n'y figurent pas : un
     * brouillon n'engage rien, un produit suspendu ou retire ne s'ouvre plus. Un catalogue qui les
     * montrerait ferait saisir des ouvertures que le socle refuserait ensuite.
     */
    public static List<Openable> openable(Connection c, UUID legalEntityId, LocalDate on) {
        List<Openable> catalogue = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT ON (code) code, label, product_type, currency, valid_from, valid_to"
            + " FROM product_version"
            + " WHERE legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)"
            + " ORDER BY code, valid_from DESC")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, on);
            ps.setObject(3, on);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    catalogue.add(new Openable(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du catalogue produit", e);
        }
        return List.copyOf(catalogue);
    }

    /**
     * Un produit ouvrable.
     *
     * @param code        code du produit, celui qu'une ouverture de compte cite
     * @param label       intitule lisible
     * @param productType famille du produit
     * @param currency    devise du produit ; un compte s'ouvre dans celle-la
     * @param validFrom   entree en vigueur de la version retenue
     * @param validTo     fin de validite, nulle quand la version est sans terme
     */
    public record Openable(String code, String label, String productType, String currency,
                           LocalDate validFrom, LocalDate validTo) {}

    public static void activate(Connection c, UUID versionId, UUID approverId) {
        validate(c, versionId);
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
    /**
     * Rattache un compte a un produit.
     *
     * <p>Le compte doit exister, et le produit exister pour son entite et dans sa devise. Un
     * compte en XOF rattache a un produit en EUR serait remunere au bareme de l'un sur les soldes
     * de l'autre, et rien dans l'ecriture ne le dirait.
     */
    public static void assignProduct(Connection c, UUID accountId, String productCode,
                                     LocalDate from, LocalDate to) {
        Account account = Accounts.loadAll(c, List.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Compte " + accountId + " inconnu.");
        }
        requireProductCurrency(c, account.legalEntityId(), productCode, account.currency().code(),
                               "le compte " + account.code());
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

    private static void insertTiers(Connection c, UUID versionId, String purpose,
                                    List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }
        // Valide la contiguite des tranches des la saisie : un bareme lacunaire est refuse au
        // deploiement du parametrage, et non decouvert au milieu d'un TFJ.
        new TieredRate(tiers, TieringMode.PROGRESSIVE);

        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_rate_tier(product_version_id, purpose, tier_order, from_amount,"
            + " to_amount, annual_rate_percent) VALUES (?,?,?,?,?,?)")) {
            for (int i = 0; i < tiers.size(); i++) {
                Tier tier = tiers.get(i);
                ps.setObject(1, versionId);
                ps.setString(2, purpose);
                ps.setInt(3, i);
                ps.setBigDecimal(4, tier.from());
                ps.setBigDecimal(5, tier.to());
                ps.setBigDecimal(6, tier.annualRatePercent());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Insertion du bareme " + purpose, e);
        }
    }

    /**
     * Exige qu'un produit existe pour l'entite, et dans la devise attendue.
     *
     * <p>Toutes les versions d'un code sont regardees, pas seulement celle en vigueur : un produit
     * ne change pas de devise au fil de ses versions, et si l'une d'elles differe, c'est le
     * parametrage qui est incoherent, pas le rattachement.
     */
    public static void requireProductCurrency(Connection c, UUID legalEntityId, String productCode,
                                              String currency, String subject) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT currency FROM product_version"
            + " WHERE legal_entity_id = ? AND code = ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, productCode);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    if (!currency.equals(rs.getString(1))) {
                        throw new IllegalArgumentException(
                            "Le produit " + productCode + " est en " + rs.getString(1)
                            + " alors que " + subject + " est en " + currency + " : il serait "
                            + "applique au bareme de l'un sur les montants de l'autre.");
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException(
                        "Aucun produit " + productCode + " pour l'entite " + legalEntityId
                        + " : " + subject + " serait rattache a un produit qui n'existe pas, et "
                        + "chaque arrete le signalerait sans pouvoir le corriger.");
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle du produit " + productCode, e);
        }
    }

    /**
     * Confronte une version a la famille de son type.
     *
     * @throws ProductFamily.IncompleteProductException avec la liste complete de ce qui manque
     */
    private static void validate(Connection c, UUID versionId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT code, product_type, legal_entity_id, currency FROM product_version"
            + " WHERE id = ?")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;                       // absente : l'activation echouera d'elle-meme
                }
                UUID entity = rs.getObject(3, UUID.class);
                String currency = rs.getString(4);
                ProductFamilies.require(rs.getString(2))
                    .validate(rs.getString(1), loadParameters(c, versionId),
                              loadTierPurposes(c, versionId),
                              (name, value) -> accountProblem(c, name, value, entity, currency));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Controle du parametrage de la version " + versionId, e);
        }
    }

    /**
     * Ce qui rend un compte impropre a recevoir les ecritures d'un produit.
     *
     * <p>Chacun de ces defauts etait refuse par le ledger a la premiere ecriture, de nuit, sur une
     * etape bloquante ; il l'est maintenant devant celui qui parametre.
     */
    private static Optional<String> accountProblem(Connection c, String parameter, String value,
                                                   UUID entity, String currency) {
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return Optional.of(parameter + " : « " + value + " » n'est pas un identifiant de compte");
        }
        Account account = Accounts.loadAll(c, List.of(id)).get(id);
        if (account == null) {
            return Optional.of(parameter + " : compte " + value + " inconnu");
        }
        if (!account.legalEntityId().equals(entity)) {
            return Optional.of(parameter + " : le compte " + account.code()
                               + " appartient a une autre entite juridique");
        }
        if (!account.currency().code().equals(currency)) {
            return Optional.of(parameter + " : le compte " + account.code() + " est tenu en "
                               + account.currency() + " alors que le produit est en " + currency);
        }
        if (account.kind() != AccountKind.GL) {
            return Optional.of(parameter + " : le compte " + account.code() + " est de nature "
                               + account.kind() + ", un compte general est attendu");
        }
        if (!account.postable()) {
            return Optional.of(parameter + " : le compte " + account.code()
                               + " n'est pas imputable");
        }
        if (!account.status().acceptsPosting()) {
            return Optional.of(parameter + " : le compte " + account.code() + " est au statut "
                               + account.status());
        }
        return Optional.empty();
    }

    /** Discriminants des baremes portes par la version : « INTEREST », « FEE:TENUE »... */
    private static Set<String> loadTierPurposes(Connection c, UUID versionId) {
        Set<String> purposes = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT purpose FROM product_rate_tier WHERE product_version_id = ?")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    purposes.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des baremes de la version " + versionId, e);
        }
        return purposes;
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

    /** En-tete d'une version : ce qu'il faut savoir d'elle avant de decider de son activation. */
    public record VersionHeader(UUID id, UUID legalEntityId, String code, String productType,
                                String status, UUID createdBy) {}

    // ------------------------------------------------------------------ lecture du parametrage

    /**
     * Une version telle qu'on la parcourt : son en-tete, sans ses parametres.
     *
     * <p>Toutes les versions figurent ici, brouillons compris — c'est ce qui distingue cette
     * lecture de {@link #openable}. L'une sert le guichet, qui ne doit voir que ce qui s'ouvre ;
     * l'autre sert celui qui parametre, qui doit voir ce qu'il a redige et qui attend un second
     * regard. Les confondre a longtemps oblige a retrouver l'identifiant d'un brouillon dans la
     * reponse du POST qui l'avait cree : perdu au rechargement de la page, le brouillon devenait
     * inactivable.
     */
    public record VersionSummary(UUID id, String code, String productType, String label,
                                 String currency, LocalDate validFrom, LocalDate validTo,
                                 String status, UUID createdBy, Instant createdAt,
                                 UUID approvedBy, Instant approvedAt) {}

    /** Les versions d'une entite, filtrees par code et par etat ; les plus recentes d'abord. */
    public static List<VersionSummary> versions(Connection c, UUID legalEntityId, String code,
                                                String status) {
        List<VersionSummary> versions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, code, product_type, label, currency, valid_from, valid_to, status,"
            + " created_by, created_at, approved_by, approved_at"
            + "  FROM product_version"
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
                    versions.add(summary(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des versions de produit", e);
        }
        return List.copyOf(versions);
    }

    /**
     * Une version en entier : en-tete, parametres, baremes.
     *
     * <p>C'est la lecture qui manquait pour qu'un parametrage soit relisible. Sans elle, un taux
     * active se verifiait en interrogeant la base, et une nouvelle version se saisissait de
     * memoire — la faute de frappe se decouvrant au premier arrete qui l'appliquait.
     */
    public record Version(VersionSummary header, Map<String, String> parameters,
                          Map<String, List<Tier>> tiers) {}

    public static Optional<Version> version(Connection c, UUID legalEntityId, UUID versionId) {
        VersionSummary header;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, code, product_type, label, currency, valid_from, valid_to, status,"
            + " created_by, created_at, approved_by, approved_at"
            + "  FROM product_version WHERE id = ? AND legal_entity_id = ?")) {
            ps.setObject(1, versionId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                header = summary(rs);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la version " + versionId, e);
        }
        return Optional.of(new Version(header, loadParameters(c, versionId),
                                       loadTiersByPurpose(c, versionId)));
    }

    private static VersionSummary summary(ResultSet rs) throws SQLException {
        return new VersionSummary(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class),
            rs.getString(8), rs.getObject(9, UUID.class), instant(rs, 10),
            rs.getObject(11, UUID.class), instant(rs, 12));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        java.sql.Timestamp stamp = rs.getTimestamp(column);
        return stamp == null ? null : stamp.toInstant();
    }

    private static Map<String, List<Tier>> loadTiersByPurpose(Connection c, UUID versionId) {
        Map<String, List<Tier>> schedules = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT purpose, from_amount, to_amount, annual_rate_percent FROM product_rate_tier"
            + " WHERE product_version_id = ? ORDER BY purpose, tier_order")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schedules.computeIfAbsent(rs.getString(1), unused -> new ArrayList<>())
                        .add(new Tier(rs.getBigDecimal(2), rs.getBigDecimal(3),
                                      rs.getBigDecimal(4)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des baremes de la version " + versionId, e);
        }
        return Map.copyOf(schedules);
    }

    // ------------------------------------------------------------------ fin de vie

    /**
     * Ferme la validite d'une version active.
     *
     * <h2>Pourquoi un statut ne suffirait pas</h2>
     *
     * <p>La table declare {@code SUSPENDED} et {@code WITHDRAWN}, et rien ne les pose. Ce n'est pas
     * un oubli : {@link #resolveAt} n'accepte qu'une version {@code ACTIVE}, et tout compte
     * rattache a ce produit resout son parametrage a <b>chaque date de valeur traitee</b>, y
     * compris passee. Sortir une version de l'etat actif ferait donc echouer l'arrete de tous les
     * comptes qui la citent, et rendrait irrejouable tout ce qu'elle a produit. Un produit ne se
     * retire pas : sa validite se ferme.
     *
     * <h2>Pourquoi la date ne peut pas etre passee</h2>
     *
     * <p>C'est la regle qui fonde le parametrage date : un arrete rejoue doit produire exactement
     * les memes montants. Fermer une version a une date deja traitee changerait ce qu'un rejeu
     * resoudrait — donc les montants. La borne est la date comptable de l'entite, et non le jour
     * civil : c'est elle qui dit ou en est la banque.
     *
     * <h2>Ce que la fermeture debloque</h2>
     *
     * <p>Une version active sans terme interdit d'en activer une autre pour le meme code — la
     * contrainte d'exclusion refuse deux validites qui se chevauchent. Sans cette fermeture, un
     * produit ouvert sans date de fin ne pouvait <b>plus jamais</b> changer de parametrage.
     */
    public static void close(Connection c, UUID legalEntityId, UUID versionId, LocalDate validTo,
                             UUID actorId) {
        LocalDate businessDate = businessDateOf(c, legalEntityId);
        if (validTo.isBefore(businessDate)) {
            throw new IllegalArgumentException(
                "Fermeture au " + validTo + " alors que la banque en est au " + businessDate
                + " : un arrete deja produit resoudrait un autre parametrage au rejeu, donc "
                + "d'autres montants. La fermeture prend effet a la date comptable ou apres.");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE product_version SET valid_to = ?"
            + " WHERE id = ? AND legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)")) {
            ps.setObject(1, validTo);
            ps.setObject(2, versionId);
            ps.setObject(3, legalEntityId);
            ps.setObject(4, validTo);
            ps.setObject(5, validTo);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Version " + versionId + " introuvable, non active, ou deja fermee a cette "
                    + "date ou avant. Une fermeture ne raccourcit pas une validite en deca de son "
                    + "entree en vigueur.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Fermeture de la version " + versionId, e);
        }
        audit(c, versionId, "CLOSE", actorId, "validite fermee au " + validTo);
    }

    /**
     * Retire un brouillon abandonne.
     *
     * <p>Un brouillon n'engage rien : celui qui l'a redige peut le retirer seul. On ne le supprime
     * pas — ce qui a ete saisi une fois se relit, et un parametrage retire explique pourquoi une
     * version attendue n'existe pas.
     */
    public static void withdrawDraft(Connection c, UUID legalEntityId, UUID versionId,
                                     UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE product_version SET status = 'WITHDRAWN'"
            + " WHERE id = ? AND legal_entity_id = ? AND status = 'DRAFT'")) {
            ps.setObject(1, versionId);
            ps.setObject(2, legalEntityId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Version " + versionId + " introuvable ou deja sortie de l'etat brouillon. "
                    + "Une version activee ne se retire pas : sa validite se ferme.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Retrait du brouillon " + versionId, e);
        }
        audit(c, versionId, "WITHDRAW", actorId, null);
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

    public static java.util.Optional<VersionHeader> findVersion(Connection c, UUID versionId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, legal_entity_id, code, product_type, status, created_by"
            + "  FROM product_version WHERE id = ?")) {
            ps.setObject(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new VersionHeader(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la version " + versionId, e);
        }
    }
}
