package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.ledger.store.Database;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Conditions d'interet lues dans la product factory, a la date de valeur traitee.
 *
 * <p>Les resolutions sont mises en cache pour la duree du traitement : un calcul sur 365 journees
 * ne declenche pas 365 lectures. Le cache est <b>local a l'instance</b> et donc a une execution —
 * un parametrage modifie entre deux traitements est vu par le suivant, jamais au milieu d'un
 * traitement en cours, ce qui garantirait sinon des montants incoherents au sein d'un meme arrete.
 */
public final class CatalogTermsResolver implements InterestTermsResolver {

    private final Database database;
    private final UUID legalEntityId;
    private final UUID accountId;
    private final Map<LocalDate, InterestTerms> cache = new HashMap<>();

    public CatalogTermsResolver(Database database, UUID legalEntityId, UUID accountId) {
        this.database = database;
        this.legalEntityId = legalEntityId;
        this.accountId = accountId;
    }

    @Override
    public InterestTerms termsAt(LocalDate valueDate) {
        return cache.computeIfAbsent(valueDate,
            date -> database.inTransaction(c -> build(
                ProductCatalog.resolveForAccount(c, legalEntityId, accountId, date))));
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
