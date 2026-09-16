package io.corebanking.deposits;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Parametres d'un produit de depot a terme.
 *
 * <p>Ce que le produit dit, et ce qu'il ne dit pas : il propose le <b>taux de reference</b> du
 * jour, le plafond de ce qu'une agence peut consentir au-dela, les bornes de duree et de montant,
 * le taux servi a qui ne tient pas la duree, et les comptes ou les interets se constatent. Ce
 * qu'il ne dit pas, c'est le taux d'un contrat deja souscrit : celui-la est fige au contrat, et le
 * bareme peut changer le lendemain sans le toucher.
 *
 * <p>Un test du module tient l'accord entre ces constantes et le descripteur de la famille
 * {@code TERM_DEPOSIT} : un parametre lu ici et absent du descripteur serait refuse a
 * l'activation, un parametre declare et lu par personne serait un parametre mort.
 */
public final class TermDepositCatalog {

    public static final String P_RATE               = "term.rate";
    public static final String P_MAX_RATE           = "term.max_rate";
    public static final String P_PENALTY_RATE       = "term.penalty_rate";
    public static final String P_DAY_COUNT          = "term.day_count";
    public static final String P_MIN_AMOUNT         = "term.min_amount";
    public static final String P_MIN_MONTHS         = "term.min_months";
    public static final String P_MAX_MONTHS         = "term.max_months";
    public static final String P_ACCRUED_INTEREST   = "term.accrued_interest";
    public static final String P_INTEREST_EXPENSE   = "term.interest_expense";
    public static final String P_WITHHOLDING_RATE   = "term.withholding_rate";
    public static final String P_WITHHOLDING_ACCOUNT = "term.withholding_account";
    public static final String P_RENEWAL            = "term.renewal";

    private TermDepositCatalog() {}

    /** Taux de reference du produit, en pourcentage annuel. */
    public static BigDecimal ratePercent(ProductVersion product) {
        return product.parameters().requireDecimal(P_RATE);
    }

    /**
     * Plafond de taux que la banque s'autorise a consentir sur ce produit ; a defaut, le taux de
     * reference lui-meme — un produit qui ne declare pas de plafond n'en laisse pas negocier.
     */
    public static BigDecimal maxRatePercent(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_MAX_RATE) ? parameters.requireDecimal(P_MAX_RATE)
                                          : ratePercent(product);
    }

    /**
     * Taux servi a qui ne tient pas la duree ; a defaut zero. Servir le taux convenu a celui qui
     * reprend ses fonds avant terme reviendrait a le servir a tout le monde : la duree est la
     * contrepartie du prix.
     */
    public static BigDecimal penaltyRatePercent(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_PENALTY_RATE) ? parameters.requireDecimal(P_PENALTY_RATE)
                                              : BigDecimal.ZERO;
    }

    public static DayCountConvention dayCount(ProductVersion product) {
        return DayCountConvention.valueOf(product.parameters().requireString(P_DAY_COUNT));
    }

    public static Money minimumAmount(ProductVersion product, CurrencyRef currency) {
        return Money.of(product.parameters().requireDecimal(P_MIN_AMOUNT), currency);
    }

    public static int minimumMonths(ProductVersion product) {
        return positive(product, P_MIN_MONTHS);
    }

    public static int maximumMonths(ProductVersion product) {
        return positive(product, P_MAX_MONTHS);
    }

    /** Compte d'interets courus non echus : le sous-livre du DAT, au passif. */
    public static UUID accruedInterest(ProductVersion product) {
        return product.parameters().requireUuid(P_ACCRUED_INTEREST);
    }

    /** Charge d'interets : ce que le depot coute a la banque, reconnu jour apres jour. */
    public static UUID interestExpense(ProductVersion product) {
        return product.parameters().requireUuid(P_INTEREST_EXPENSE);
    }

    public static BigDecimal withholdingPercent(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_WITHHOLDING_RATE) ? parameters.requireDecimal(P_WITHHOLDING_RATE)
                                                  : BigDecimal.ZERO;
    }

    public static Optional<UUID> withholdingAccount(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return parameters.has(P_WITHHOLDING_ACCOUNT)
            ? Optional.of(parameters.requireUuid(P_WITHHOLDING_ACCOUNT)) : Optional.empty();
    }

    /**
     * Ce que le produit admet comme reconduction : {@code NONE} interdit le renouvellement — un
     * client dont le DAT se reconduirait sans qu'il l'ait demande verrait son argent bloque une
     * periode de plus.
     */
    public static boolean renewalAllowed(ProductVersion product) {
        ParameterSet parameters = product.parameters();
        return !parameters.has(P_RENEWAL)
               || !"NONE".equalsIgnoreCase(parameters.requireString(P_RENEWAL));
    }

    private static int positive(ProductVersion product, String name) {
        int value = Integer.parseInt(product.parameters().requireString(name));
        if (value <= 0) {
            throw new IllegalArgumentException(
                "Produit " + product.code() + " : " + name + " doit etre positif");
        }
        return value;
    }
}
