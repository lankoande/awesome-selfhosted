package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.OverdraftRate;
import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductNotFoundException;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conditions d'interet d'un lot de comptes, resolues depuis la product factory.
 *
 * <p>Deux mises en cache, et chacune supprime un acces par compte :
 *
 * <ul>
 *   <li><b>Le rattachement compte vers produit</b> est charge en une requete pour tout le lot,
 *       ainsi que les autorisations de decouvert propres aux comptes.</li>
 *   <li><b>Le parametrage</b> est mis en cache par couple produit et date. Deux millions de comptes
 *       rattaches au meme produit partagent une seule lecture, la ou une resolution par compte en
 *       demanderait quatre chacune — rattachement, version, parametres, bareme.</li>
 * </ul>
 *
 * <p>Le cache est local a l'instance, donc a une execution : un parametrage modifie entre deux
 * traitements est vu par le suivant, jamais au milieu d'un arrete en cours, ou il produirait des
 * montants incoherents entre deux comptes du meme lot.
 *
 * <h2>Deux cotes</h2>
 *
 * <p>Le bloc {@code interest.*} du produit decrit le cote principal — crediteur pour un compte
 * remunere. Le bloc {@code overdraft.*}, facultatif, decrit les agios : taux dans l'autorisation
 * et au-dela, comptes d'imputation, arrete et taxe. Un compte courant porte les deux ; un compte
 * d'epargne n'a que le premier. Les deux series sont calculees separement et ne se compensent
 * jamais.
 */
public final class CatalogTermsProvider implements TermsProvider {

    private final Database database;
    private final UUID legalEntityId;
    private final Map<UUID, String> productByAccount;
    private final Map<UUID, List<Limit>> limitsByAccount;
    private final Map<String, ProductVersion> versions = new HashMap<>();
    private final Map<String, InterestTerms> primary = new HashMap<>();

    private record Limit(BigDecimal amount, LocalDate from, LocalDate to) {}

    private CatalogTermsProvider(Database database, UUID legalEntityId,
                                 Map<UUID, String> productByAccount,
                                 Map<UUID, List<Limit>> limitsByAccount) {
        this.database = database;
        this.legalEntityId = legalEntityId;
        this.productByAccount = Map.copyOf(productByAccount);
        this.limitsByAccount = Map.copyOf(limitsByAccount);
    }

    /** Charge en une requete le produit de chaque compte du lot, a la date traitee. */
    public static CatalogTermsProvider forAccounts(Database database, UUID legalEntityId,
                                                   Collection<UUID> accountIds, LocalDate date) {
        return database.inTransaction(connection -> {
            Map<UUID, String> products = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT ON (account_id) account_id, product_code FROM account_product"
                + " WHERE account_id = ANY (?) AND valid_from <= ?"
                + "   AND (valid_to IS NULL OR valid_to >= ?)"
                + " ORDER BY account_id, valid_from DESC")) {
                ps.setArray(1, connection.createArrayOf("uuid", accountIds.toArray()));
                ps.setObject(2, date);
                ps.setObject(3, date);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        products.put(rs.getObject(1, UUID.class), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Rattachement produit du lot", e);
            }
            Map<UUID, List<Limit>> limits = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT account_id, amount, valid_from, valid_to FROM overdraft_limit"
                + " WHERE account_id = ANY (?) ORDER BY account_id, valid_from")) {
                ps.setArray(1, connection.createArrayOf("uuid", accountIds.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        limits.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Limit(rs.getBigDecimal(2), rs.getObject(3, LocalDate.class),
                                           rs.getObject(4, LocalDate.class)));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Autorisations de decouvert du lot", e);
            }
            return new CatalogTermsProvider(database, legalEntityId, products, limits);
        });
    }

    @Override
    public InterestTerms termsFor(UUID accountId, LocalDate valueDate) {
        String productCode = productOf(accountId, valueDate);
        return primary.computeIfAbsent(productCode + "@" + valueDate,
                                       key -> primaryTerms(version(productCode, valueDate)));
    }

    /**
     * Conditions des agios d'un compte a une journee, si son produit en declare. L'autorisation
     * est celle du compte a cette journee, a defaut celle du produit.
     */
    public Optional<InterestTerms> overdraftTermsFor(UUID accountId, LocalDate valueDate) {
        String productCode = productOf(accountId, valueDate);
        ProductVersion version = version(productCode, valueDate);
        return overdraftTerms(version, limitOf(accountId, version, valueDate));
    }

    /**
     * Conditions d'un compte pour un cote donne : le bloc principal s'il porte ce cote, le bloc
     * d'agios pour le cote debiteur sinon ; absentes si le produit ne remunere pas ce cote.
     */
    public Optional<InterestTerms> termsForSide(UUID accountId, LocalDate valueDate,
                                                AccrualSide side) {
        InterestTerms primaryTerms = termsFor(accountId, valueDate);
        if (primaryTerms.side() == side) {
            return Optional.of(primaryTerms);
        }
        return side == AccrualSide.DEBTOR ? overdraftTermsFor(accountId, valueDate)
                                          : Optional.empty();
    }

    /** Vue du meme lot sur le cote des agios : refuse un compte dont le produit n'en declare pas. */
    public TermsProvider overdraft() {
        return (accountId, valueDate) -> overdraftTermsFor(accountId, valueDate)
            .orElseThrow(() -> new IllegalStateException(
                "le produit " + productOf(accountId, valueDate) + " ne declare pas d'agios"));
    }

    /** Comptes du lot dont le produit, a la date traitee, declare des agios. */
    public List<UUID> overdraftAccounts(LocalDate date) {
        List<UUID> accounts = new ArrayList<>();
        Map<String, Boolean> byProduct = new HashMap<>();
        for (Map.Entry<UUID, String> entry : productByAccount.entrySet()) {
            boolean declares = byProduct.computeIfAbsent(entry.getValue(), code -> {
                try {
                    return version(code, date).parameters().has(ProductCatalog.P_OD_RATE);
                } catch (ProductNotFoundException e) {
                    return false;      // signale par le cote principal, pas ici
                }
            });
            if (declares) {
                accounts.add(entry.getKey());
            }
        }
        return accounts;
    }

    // ------------------------------------------------------------------ construction

    private String productOf(UUID accountId, LocalDate valueDate) {
        String productCode = productByAccount.get(accountId);
        if (productCode == null) {
            throw new ProductNotFoundException("rattache au compte " + accountId, valueDate);
        }
        return productCode;
    }

    private ProductVersion version(String productCode, LocalDate date) {
        return versions.computeIfAbsent(productCode + "@" + date,
            key -> database.inTransaction(connection -> ProductCatalog.resolveAt(
                connection, legalEntityId, productCode, date)));
    }

    private BigDecimal limitOf(UUID accountId, ProductVersion version, LocalDate day) {
        BigDecimal limit = null;
        for (Limit candidate : limitsByAccount.getOrDefault(accountId, List.of())) {
            if (!candidate.from().isAfter(day) && (candidate.to() == null
                                                   || !candidate.to().isBefore(day))) {
                limit = candidate.amount();          // la plus recente l'emporte : tri croissant
            }
        }
        if (limit != null) {
            return limit;
        }
        ParameterSet parameters = version.parameters();
        return parameters.has(ProductCatalog.P_OD_LIMIT)
            ? parameters.requireDecimal(ProductCatalog.P_OD_LIMIT) : BigDecimal.ZERO;
    }

    /** Conditions du cote principal d'une version de produit. */
    public static InterestTerms primaryTerms(ProductVersion version) {
        ParameterSet parameters = version.parameters();
        RateSchedule rates = version.tieredSchedule()
            .orElseGet(() -> new FlatRate(parameters.requireDecimal(ProductCatalog.P_RATE)));

        SettlementTerms settlement = parameters.has(ProductCatalog.P_CAPITALISATION)
            ? SettlementTerms.capitalisation(
                parameters.requireEnum(ProductCatalog.P_CAPITALISATION, Periodicity.class),
                parameters.has(ProductCatalog.P_WITHHOLDING)
                    ? parameters.requireString(ProductCatalog.P_WITHHOLDING) : null)
            : null;

        return new InterestTerms(
            rates,
            parameters.requireEnum(ProductCatalog.P_DAY_COUNT, DayCountConvention.class),
            parameters.requireEnum(ProductCatalog.P_SIDE, AccrualSide.class),
            parameters.requireUuid(ProductCatalog.P_DEBIT_ACCOUNT),
            parameters.requireUuid(ProductCatalog.P_CREDIT_ACCOUNT),
            settlement);
    }

    /**
     * Conditions des agios d'une version de produit, pour une autorisation donnee ; absentes si le
     * produit n'en declare pas.
     *
     * <p>Un produit dont le cote principal est deja debiteur ne peut pas declarer d'agios en plus :
     * ce seraient deux series debitrices sur le meme compte, et le client paierait deux fois.
     */
    public static Optional<InterestTerms> overdraftTerms(ProductVersion version, BigDecimal limit) {
        ParameterSet parameters = version.parameters();
        if (!parameters.has(ProductCatalog.P_OD_RATE)) {
            return Optional.empty();
        }
        if (parameters.requireEnum(ProductCatalog.P_SIDE, AccrualSide.class) == AccrualSide.DEBTOR) {
            throw new IllegalStateException(
                "le produit " + version.code() + " remunere deja le cote debiteur : le bloc "
                + "d'agios ferait une seconde serie debitrice sur le meme compte");
        }
        BigDecimal rate = parameters.requireDecimal(ProductCatalog.P_OD_RATE);
        BigDecimal excess = parameters.has(ProductCatalog.P_OD_EXCESS_RATE)
            ? parameters.requireDecimal(ProductCatalog.P_OD_EXCESS_RATE) : rate;
        DayCountConvention dayCount = parameters.has(ProductCatalog.P_OD_DAY_COUNT)
            ? parameters.requireEnum(ProductCatalog.P_OD_DAY_COUNT, DayCountConvention.class)
            : parameters.requireEnum(ProductCatalog.P_DAY_COUNT, DayCountConvention.class);

        SettlementTerms settlement = SettlementTerms.overdraftCharge(
            parameters.requireEnum(ProductCatalog.P_OD_SETTLEMENT, Periodicity.class),
            parameters.has(ProductCatalog.P_OD_TAX_RATE)
                ? parameters.requireDecimal(ProductCatalog.P_OD_TAX_RATE) : BigDecimal.ZERO,
            parameters.has(ProductCatalog.P_OD_TAX_ACCOUNT)
                ? parameters.requireUuid(ProductCatalog.P_OD_TAX_ACCOUNT) : null);

        return Optional.of(new InterestTerms(
            new OverdraftRate(limit.setScale(5, java.math.RoundingMode.HALF_EVEN), rate, excess),
            dayCount, AccrualSide.DEBTOR,
            parameters.requireUuid(ProductCatalog.P_OD_DEBIT_ACCOUNT),
            parameters.requireUuid(ProductCatalog.P_OD_CREDIT_ACCOUNT),
            settlement));
    }
}
