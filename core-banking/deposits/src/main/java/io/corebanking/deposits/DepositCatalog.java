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

    private DepositCatalog() {}

    public static Money withdrawalFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_WITHDRAWAL_FEE, currency);
    }

    public static Money transferFee(ProductVersion product, CurrencyRef currency) {
        return flat(product.parameters(), P_TRANSFER_FEE, currency);
    }

    /** Compte de produit des frais d'operation, exige des qu'un frais est parametre. */
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
