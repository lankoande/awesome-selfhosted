package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductNotFoundException;
import io.corebanking.product.ProductVersion;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Conditions d'interet d'un lot de comptes, resolues depuis la product factory.
 *
 * <p>Deux mises en cache, et chacune supprime un acces par compte :
 *
 * <ul>
 *   <li><b>Le rattachement compte vers produit</b> est charge en une requete pour tout le lot.</li>
 *   <li><b>Le parametrage</b> est mis en cache par couple produit et date. Deux millions de comptes
 *       rattaches au meme produit partagent une seule lecture, la ou une resolution par compte en
 *       demanderait quatre chacune — rattachement, version, parametres, bareme.</li>
 * </ul>
 *
 * <p>Le cache est local a l'instance, donc a une execution : un parametrage modifie entre deux
 * traitements est vu par le suivant, jamais au milieu d'un arrete en cours, ou il produirait des
 * montants incoherents entre deux comptes du meme lot.
 */
public final class CatalogTermsProvider implements TermsProvider {

    private final Database database;
    private final UUID legalEntityId;
    private final Map<UUID, String> productByAccount;
    private final Map<String, InterestTerms> cache = new HashMap<>();

    private CatalogTermsProvider(Database database, UUID legalEntityId,
                                 Map<UUID, String> productByAccount) {
        this.database = database;
        this.legalEntityId = legalEntityId;
        this.productByAccount = Map.copyOf(productByAccount);
    }

    /** Charge en une requete le produit de chaque compte du lot, a la date traitee. */
    public static CatalogTermsProvider forAccounts(Database database, UUID legalEntityId,
                                                   Collection<UUID> accountIds, LocalDate date) {
        Map<UUID, String> products = database.inTransaction(connection -> {
            Map<UUID, String> found = new LinkedHashMap<>();
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
                        found.put(rs.getObject(1, UUID.class), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Rattachement produit du lot", e);
            }
            return found;
        });
        return new CatalogTermsProvider(database, legalEntityId, products);
    }

    @Override
    public InterestTerms termsFor(UUID accountId, LocalDate valueDate) {
        String productCode = productByAccount.get(accountId);
        if (productCode == null) {
            throw new ProductNotFoundException("rattache au compte " + accountId, valueDate);
        }
        return cache.computeIfAbsent(productCode + "@" + valueDate,
            key -> database.inTransaction(connection -> build(
                ProductCatalog.resolveAt(connection, legalEntityId, productCode, valueDate))));
    }

    private InterestTerms build(ProductVersion version) {
        ParameterSet parameters = version.parameters();
        RateSchedule rates = version.tieredSchedule()
            .orElseGet(() -> new FlatRate(parameters.requireDecimal(ProductCatalog.P_RATE)));

        return new InterestTerms(
            rates,
            parameters.requireEnum(ProductCatalog.P_DAY_COUNT, DayCountConvention.class),
            parameters.requireEnum(ProductCatalog.P_SIDE, AccrualSide.class),
            parameters.requireUuid(ProductCatalog.P_DEBIT_ACCOUNT),
            parameters.requireUuid(ProductCatalog.P_CREDIT_ACCOUNT));
    }
}
