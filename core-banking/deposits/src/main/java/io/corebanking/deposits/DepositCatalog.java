package io.corebanking.deposits;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Parametres de compte de depot portes par la version de produit : frais d'operation, taxe,
 * dormance. Declares dans les familles {@code CURRENT_ACCOUNT} et {@code SAVINGS_ACCOUNT} ; un
 * test tient l'accord entre ces constantes et le descripteur.
 */
public final class DepositCatalog {

    public static final String P_WITHDRAWAL_FEE    = "ops.withdrawal_fee";
    public static final String P_TRANSFER_FEE      = "ops.transfer_fee";
    public static final String P_FEE_INCOME        = "ops.fee_income_account";
    public static final String P_TAX_RATE          = "ops.tax_rate";
    public static final String P_TAX_ACCOUNT       = "ops.tax_account";
    public static final String P_DORMANCY_MONTHS   = "dormancy.months";
    public static final String P_TRANSACTION_MAX   = "ops.transaction_max";
    public static final String P_DAILY_DEBIT_MAX   = "ops.daily_debit_max";
    public static final String P_MONTHLY_DEBIT_MAX = "ops.monthly_debit_max";
    public static final String P_PAYMENT_FEE       = "ops.payment_fee";
    public static final String P_PAYMENT_CLEARING  = "ops.payment_clearing_account";
    public static final String P_CHEQUE_BOOK_FEE   = "ops.cheque_book_fee";
    public static final String P_CHEQUE_COLLECTION = "ops.cheque_collection_account";
    public static final String P_DIRECT_DEBIT_FEE  = "ops.direct_debit_fee";
    public static final String P_DIRECT_DEBIT_COLLECTION = "ops.direct_debit_collection_account";

    private DepositCatalog() {}

    public static Money withdrawalFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_WITHDRAWAL_FEE, currency);
    }

    public static Money transferFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_TRANSFER_FEE, currency);
    }

    /** Compte de produit des frais d'operation, exige des qu'un frais est parametre. */
    public static Money paymentFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_PAYMENT_FEE, currency);
    }

    public static Money chequeBookFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_CHEQUE_BOOK_FEE, currency);
    }

    /** Frais d'un prelevement, recu ou emis, a la charge du client de la banque. */
    public static Money directDebitFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_DIRECT_DEBIT_FEE, currency);
    }

    /** Compte de prelevements a l'encaissement ; vide, le produit n'emet pas de prelevement. */
    public static Optional<UUID> directDebitCollection(ProductVersion product) {
        var parameters = product.parameters();
        return parameters.has(P_DIRECT_DEBIT_COLLECTION)
            ? Optional.of(parameters.requireUuid(P_DIRECT_DEBIT_COLLECTION)) : Optional.empty();
    }

    /** Compte de cheques a l'encaissement ; vide, le produit n'admet pas de remise de cheque. */
    public static Optional<UUID> chequeCollection(ProductVersion product) {
        var parameters = product.parameters();
        return parameters.has(P_CHEQUE_COLLECTION)
            ? Optional.of(parameters.requireUuid(P_CHEQUE_COLLECTION)) : Optional.empty();
    }

    /** Compte de reglement sortant du produit ; vide, le produit n'admet pas de paiement sortant. */
    public static Optional<UUID> paymentClearing(ProductVersion product) {
        var parameters = product.parameters();
        return parameters.has(P_PAYMENT_CLEARING)
            ? Optional.of(parameters.requireUuid(P_PAYMENT_CLEARING)) : Optional.empty();
    }

    /** Un plafond du produit, dans la devise ; vide quand le produit n'en fixe pas. */
    public static Optional<Money> limit(ProductVersion product, String parameter,
                                        CurrencyRef currency) {
        var parameters = product.parameters();
        return parameters.has(parameter)
            ? Optional.of(Money.of(parameters.requireDecimal(parameter), currency))
            : Optional.empty();
    }

    public static UUID feeIncome(ProductVersion product) {
        return product.parameters().requireUuid(P_FEE_INCOME);
    }

    public static BigDecimal taxRatePercent(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_TAX_RATE) ? parameters.requireDecimal(P_TAX_RATE) : BigDecimal.ZERO;
    }

    public static UUID taxAccount(ProductVersion product) {
        return product.parameters().requireUuid(P_TAX_ACCOUNT);
    }

    /** Mois sans operation client avant dormance ; absent si le produit ne la connait pas. */
    public static Optional<Integer> dormancyMonths(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        if (!parameters.has(P_DORMANCY_MONTHS)) {
            return Optional.empty();
        }
        int months = Integer.parseInt(parameters.requireString(P_DORMANCY_MONTHS));
        if (months <= 0) {
            throw new IllegalArgumentException(
                "Produit " + product.code() + " : " + P_DORMANCY_MONTHS + " doit etre positif");
        }
        return Optional.of(months);
    }

    private static Money flat(ParameterSet parameters, String name, CurrencyRef currency) {
        return parameters.has(name) ? Money.of(parameters.requireDecimal(name), currency)
                                    : Money.zero(currency);
    }
}
