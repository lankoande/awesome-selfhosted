package io.corebanking.loan.service;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.loan.AllocationOrder;
import io.corebanking.loan.LateInterestBasis;
import io.corebanking.loan.LatePolicy;
import io.corebanking.loan.PenaltyMode;
import io.corebanking.loan.RateAnnualisation;
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
    public static final String P_ACCRUED_INTEREST = "loan.accrued_interest";
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
    public static final String P_RISK_PROFILE     = "loan.risk_profile";
    public static final String P_PROVISION_EXPENSE = "loan.provision_expense";
    public static final String P_PROVISION_ALLOWANCE = "loan.provision_allowance";
    public static final String P_PROVISION_RELEASE = "loan.provision_release";
    public static final String P_RESERVED_INTEREST = "loan.reserved_interest";
    public static final String P_USURY_RATE       = "loan.usury_rate";
    public static final String P_TEG_METHOD       = "loan.teg_method";
    public static final String P_PREPAY_RATE      = "loan.prepayment_indemnity_rate";
    public static final String P_PREPAY_CAP_PCT   = "loan.prepayment_cap_percent";
    public static final String P_PREPAY_CAP_MONTHS = "loan.prepayment_cap_months";
    public static final String P_PREPAY_INDEMNITY_ACCOUNT = "loan.prepayment_indemnity_account";

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

    /**
     * Convention d'annualisation du taux effectif.
     *
     * <p>Elle est declaree par le produit parce qu'elle est imposee par le regulateur du pays, et
     * qu'un socle multi-pays ne peut pas en presumer. A defaut, la convention proportionnelle —
     * celle du taux effectif global historique de la zone.
     */
    public static RateAnnualisation tegMethod(ProductVersion product) {
        return product.parameters().has(P_TEG_METHOD)
            ? product.parameters().requireEnum(P_TEG_METHOD, RateAnnualisation.class)
            : RateAnnualisation.PROPORTIONAL;
    }

    /**
     * Plafond d'usure applicable, absent si le pays n'en impose pas.
     *
     * <p>Le plafond porte sur le <b>taux effectif</b>. Le controler sur le taux nominal laisserait
     * passer tout credit dont les frais de dossier font l'essentiel du cout.
     */
    public static java.util.Optional<BigDecimal> usuryRate(ProductVersion product) {
        return product.parameters().has(P_USURY_RATE)
            ? java.util.Optional.of(product.parameters().requireDecimal(P_USURY_RATE))
            : java.util.Optional.empty();
    }

    public static BigDecimal prepaymentIndemnityRate(ProductVersion product) {
        return optionalDecimal(product, P_PREPAY_RATE);
    }

    /** Plafond legal en pourcentage du capital rembourse, nul si le pays n'en impose pas. */
    public static BigDecimal prepaymentCapPercent(ProductVersion product) {
        return optionalDecimal(product, P_PREPAY_CAP_PCT);
    }

    /** Plafond legal en mois d'interets, nul si le pays n'en impose pas. */
    public static BigDecimal prepaymentCapMonths(ProductVersion product) {
        return optionalDecimal(product, P_PREPAY_CAP_MONTHS);
    }

    /**
     * Compte de produit de l'indemnite. Exige des lors qu'une indemnite est parametree : ce n'est
     * pas un interet, et la loger sur le produit d'interets fausserait la marge d'interet.
     */
    public static UUID prepaymentIndemnity(ProductVersion product) {
        return product.parameters().requireUuid(P_PREPAY_INDEMNITY_ACCOUNT);
    }

    private static BigDecimal optionalDecimal(ProductVersion product, String name) {
        return product.parameters().has(name) ? product.parameters().requireDecimal(name) : null;
    }

    /** Code du profil de risque applique, absent si le produit n'est pas classe. */
    public static java.util.Optional<String> riskProfile(ProductVersion product) {
        return product.parameters().has(P_RISK_PROFILE)
            ? java.util.Optional.of(product.parameters().requireString(P_RISK_PROFILE))
            : java.util.Optional.empty();
    }

    public static UUID provisionExpense(ProductVersion product) {
        return product.parameters().requireUuid(P_PROVISION_EXPENSE);
    }

    public static UUID provisionAllowance(ProductVersion product) {
        return product.parameters().requireUuid(P_PROVISION_ALLOWANCE);
    }

    /** Compte de reprise sur provisions, celui de la dotation a defaut de ventilation dediee. */
    public static UUID provisionRelease(ProductVersion product) {
        return product.parameters().has(P_PROVISION_RELEASE)
            ? product.parameters().requireUuid(P_PROVISION_RELEASE) : provisionExpense(product);
    }

    public static UUID reservedInterest(ProductVersion product) {
        return product.parameters().requireUuid(P_RESERVED_INTEREST);
    }

    private static Money money(ParameterSet parameters, String name, CurrencyRef currency) {
        return parameters.has(name) ? Money.of(parameters.requireDecimal(name), currency) : null;
    }

    public static UUID accruedReceivable(ProductVersion product) {
        return product.parameters().requireUuid(P_ACCRUED);
    }

    /**
     * Compte des interets courus non echus : l'actif ou l'interet de l'echeance en cours est
     * constate jour apres jour, avant d'etre repris par la creance a l'echeance.
     */
    public static UUID accruedInterest(ProductVersion product) {
        return product.parameters().requireUuid(P_ACCRUED_INTEREST);
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
