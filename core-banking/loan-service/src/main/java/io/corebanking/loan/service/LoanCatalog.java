package io.corebanking.loan.service;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.loan.AllocationOrder;
import io.corebanking.loan.LateInterestBasis;
import io.corebanking.loan.LatePolicy;
import io.corebanking.loan.PenaltyMode;
import java.math.BigDecimal;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductVersion;
import java.util.UUID;

/**
 * Parametres de credit portes par la version de produit.
 *
 * <p>Ce que le produit fixe : les comptes d'imputation, l'ordre d'imputation d'un reglement, le
 * prelevement automatique et le delai de grace. Ce que le contrat fixe : le montant, la duree, le
 * taux, la methode d'amortissement — ils varient d'un dossier a l'autre et n'ont rien a faire dans
 * le parametrage produit.
 */
public final class LoanCatalog {

    public static final String P_ALLOCATION_ORDER = "loan.allocation_order";
    public static final String P_DIRECT_DEBIT     = "loan.direct_debit";
    public static final String P_GRACE_DAYS       = "loan.grace_days";
    public static final String P_ACCRUED          = "loan.accrued_receivable";
    public static final String P_INTEREST_INCOME  = "loan.interest_income";
    public static final String P_INSURANCE_INCOME = "loan.insurance_income";
    public static final String P_FEE_INCOME       = "loan.fee_income";
    public static final String P_TAX_ACCOUNT      = "loan.tax_account";
    public static final String P_LATE_RATE        = "loan.late_interest_rate";
    public static final String P_LATE_BASIS       = "loan.late_interest_basis";
    public static final String P_LATE_DAY_COUNT   = "loan.late_day_count";
    public static final String P_PENALTY_MODE     = "loan.penalty_mode";
    public static final String P_PENALTY_AMOUNT   = "loan.penalty_amount";
    public static final String P_PENALTY_RATE     = "loan.penalty_rate";
    public static final String P_PENALTY_FLOOR    = "loan.penalty_floor";
    public static final String P_PENALTY_CAP      = "loan.penalty_cap";
    public static final String P_MAX_RATE         = "loan.max_rate";
    public static final String P_LATE_INCOME      = "loan.late_interest_income";
    public static final String P_PENALTY_INCOME   = "loan.penalty_income";

    private LoanCatalog() {}

    /**
     * Ordre d'imputation du produit.
     *
     * <p>L'ordre du dossier de conception sert de point de depart en l'absence de parametre. Ce
     * n'est pas un repli silencieux : l'ordre par defaut est documente, il figure dans la
     * restitution du contrat, et il est refuse des qu'il est incomplet.
     */
    public static AllocationOrder allocationOrder(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_ALLOCATION_ORDER)
            ? AllocationOrder.parse(parameters.requireString(P_ALLOCATION_ORDER))
            : AllocationOrder.standard();
    }

    /** Vrai si l'echeance est prelevee d'office sur le compte de reglement a son exigibilite. */
    public static boolean directDebit(ProductVersion product) {
        return Boolean.parseBoolean(product.parameters().optionalString(P_DIRECT_DEBIT, "false"));
    }

    /** Delai de grace, en jours, avant qu'une echeance impayee ne compte comme en retard. */
    public static int graceDays(ProductVersion product) {
        return Integer.parseInt(product.parameters().optionalString(P_GRACE_DAYS, "0"));
    }

    /**
     * Regime de retard du produit, absent s'il n'en declare aucun.
     *
     * <p>L'absence est un choix licite : tous les produits ne facturent pas le retard. Elle est
     * donc distinguee d'un parametrage incomplet, qui est refuse.
     */
    public static java.util.Optional<LatePolicy> latePolicy(ProductVersion product,
                                                             CurrencyRef currency) {
        ParameterSet parameters = product.parameters();
        PenaltyMode mode = parameters.has(P_PENALTY_MODE)
            ? parameters.requireEnum(P_PENALTY_MODE, PenaltyMode.class) : PenaltyMode.NONE;
        BigDecimal rate = parameters.has(P_LATE_RATE)
            ? parameters.requireDecimal(P_LATE_RATE) : BigDecimal.ZERO;
        if (rate.signum() == 0 && mode == PenaltyMode.NONE) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new LatePolicy(
            currency, rate,
            parameters.has(P_LATE_BASIS)
                ? parameters.requireEnum(P_LATE_BASIS, LateInterestBasis.class)
                : LateInterestBasis.OVERDUE_PRINCIPAL,
            parameters.has(P_LATE_DAY_COUNT)
                ? parameters.requireEnum(P_LATE_DAY_COUNT, DayCountConvention.class)
                : DayCountConvention.ACT_365,
            graceDays(product), mode,
            money(parameters, P_PENALTY_AMOUNT, currency),
            parameters.has(P_PENALTY_RATE) ? parameters.requireDecimal(P_PENALTY_RATE)
                                           : BigDecimal.ZERO,
            money(parameters, P_PENALTY_FLOOR, currency),
            money(parameters, P_PENALTY_CAP, currency),
            parameters.has(P_MAX_RATE) ? parameters.requireDecimal(P_MAX_RATE) : BigDecimal.ZERO));
    }

    /**
     * Compte de produit des interets de retard. Exige des lors que le produit facture le retard :
     * dire qu'on le facture sans dire ou il s'impute laisserait le moteur choisir a la place du
     * plan comptable.
     */
    public static UUID lateInterestIncome(ProductVersion product) {
        return product.parameters().requireUuid(P_LATE_INCOME);
    }

    public static UUID penaltyIncome(ProductVersion product) {
        return product.parameters().has(P_PENALTY_INCOME)
            ? product.parameters().requireUuid(P_PENALTY_INCOME) : lateInterestIncome(product);
    }

    private static Money money(ParameterSet parameters, String name, CurrencyRef currency) {
        return parameters.has(name) ? Money.of(parameters.requireDecimal(name), currency) : null;
    }

    public static UUID accruedReceivable(ProductVersion product) {
        return product.parameters().requireUuid(P_ACCRUED);
    }

    public static UUID interestIncome(ProductVersion product) {
        return product.parameters().requireUuid(P_INTEREST_INCOME);
    }

    /** Compte de produit d'assurance, celui des interets a defaut de ventilation dediee. */
    public static UUID insuranceIncome(ProductVersion product) {
        return product.parameters().has(P_INSURANCE_INCOME)
            ? product.parameters().requireUuid(P_INSURANCE_INCOME) : interestIncome(product);
    }

    public static UUID feeIncome(ProductVersion product) {
        return product.parameters().has(P_FEE_INCOME)
            ? product.parameters().requireUuid(P_FEE_INCOME) : interestIncome(product);
    }

    public static UUID taxAccount(ProductVersion product) {
        return product.parameters().requireUuid(P_TAX_ACCOUNT);
    }
}
