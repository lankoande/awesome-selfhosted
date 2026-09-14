package io.corebanking.loan.service;

import io.corebanking.loan.AllocationOrder;
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
