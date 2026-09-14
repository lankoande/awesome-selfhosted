package io.corebanking.fee.service;

import io.corebanking.fee.FeeBasis;
import io.corebanking.fee.FeeTerms;
import io.corebanking.fee.FeeTiming;
import io.corebanking.fee.InsufficientFundsPolicy;
import io.corebanking.fee.Proration;
import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ParameterSet;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lecture des conditions de commission portees par une version de produit.
 *
 * <p>Un produit peut porter plusieurs commissions — tenue de compte, plus fort decouvert,
 * cotisation de carte. Elles sont declarees par {@code fee.codes} et chacune decrite par des
 * parametres prefixes de son code. Le parametrage reste ainsi dans la table generique du produit,
 * datee et versionnee comme le reste : un arrete rejoue retrouve les tarifs de l'epoque.
 *
 * <p>Toute erreur de parametrage leve ici, a la resolution, et nomme le produit et le parametre.
 * C'est l'endroit ou elle coute le moins cher : le TFJ a blanc la revele avant le TFJ reel.
 */
public final class FeeCatalog {

    /** Liste des commissions portees par le produit, separees par des virgules. */
    public static final String P_FEE_CODES = "fee.codes";

    private static final String LABEL         = "label";
    private static final String FREQUENCY     = "frequency";
    private static final String ANCHOR        = "anchor";
    private static final String TIMING        = "timing";
    private static final String BASIS         = "basis";
    private static final String AMOUNT        = "amount";
    private static final String RATE          = "rate";
    private static final String TIERING_MODE  = "tiering_mode";
    private static final String FLOOR         = "floor";
    private static final String CAP           = "cap";
    private static final String PRORATION     = "proration";
    private static final String TAX_RATE      = "tax_rate";
    private static final String INCOME_ACCOUNT = "income_account";
    private static final String TAX_ACCOUNT   = "tax_account";
    private static final String ON_INSUFFICIENT = "on_insufficient_funds";
    private static final String ARREAR_MAX_AGE  = "arrear_max_age_days";
    private static final String SCHEMA_CODE     = "schema";

    /** Decouvert autorise, ajoute au disponible lors du controle de provision. */
    public static final String P_OVERDRAFT_LIMIT = "overdraft.limit";

    /**
     * Tous les parametres qu'une commission peut porter.
     *
     * <p>Sert au controle d'accord entre ce que ce code lit et ce que la famille de produit
     * declare : un parametre lu ici et absent de la famille serait refuse a l'activation d'un
     * produit qui l'emploie, et un parametre declare par la famille mais lu par personne serait un
     * parametre mort. Les deux se detectent en confrontant les deux listes, pas en les relisant.
     */
    static java.util.Set<String> parameterNames(String feeCode) {
        String prefix = "fee." + feeCode + ".";
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String suffix : new String[] {
            LABEL, FREQUENCY, ANCHOR, TIMING, BASIS, AMOUNT, RATE, TIERING_MODE, FLOOR, CAP,
            PRORATION, TAX_RATE, INCOME_ACCOUNT, TAX_ACCOUNT, ON_INSUFFICIENT, ARREAR_MAX_AGE,
            SCHEMA_CODE}) {
            names.add(prefix + suffix);
        }
        return names;
    }

    private FeeCatalog() {}

    /** Codes des commissions declarees par le produit, dans l'ordre de declaration. */
    public static List<String> feeCodes(ProductVersion product) {
        String declared = product.parameters().optionalString(P_FEE_CODES, "");
        if (declared.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String raw : declared.split(",")) {
            String code = raw.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * Conditions d'une commission.
     *
     * @param accountOpenedAt ancrage de repli lorsque le produit n'en fixe pas : chaque compte
     *                        suit alors son propre cycle depuis son ouverture. Ce n'est pas une
     *                        valeur par defaut cachee — les deux ancrages sont des choix de
     *                        gestion legitimes, et l'absence du parametre en designe un.
     */
    public static FeeTerms resolve(Connection c, ProductVersion product, String feeCode,
                                   CurrencyRef currency, LocalDate accountOpenedAt) {
        ParameterSet parameters = product.parameters();
        String prefix = "fee." + feeCode + ".";

        FeeBasis basis = enumeration(parameters, prefix + BASIS, FeeBasis.class, FeeBasis.FLAT);
        FeeTerms.Builder builder =
            new FeeTerms.Builder(feeCode, parameters.optionalString(prefix + LABEL, feeCode),
                                 currency)
                .frequency(enumeration(parameters, prefix + FREQUENCY, Periodicity.class,
                                       Periodicity.MONTHLY))
                .timing(enumeration(parameters, prefix + TIMING, FeeTiming.class,
                                    FeeTiming.IN_ARREARS))
                .basis(basis)
                .proration(enumeration(parameters, prefix + PRORATION, Proration.class,
                                       Proration.NONE))
                .anchor(anchor(parameters, prefix + ANCHOR, accountOpenedAt))
                .taxRatePercent(parameters.has(prefix + TAX_RATE)
                                ? parameters.requireDecimal(prefix + TAX_RATE) : BigDecimal.ZERO)
                .incomeAccount(parameters.requireUuid(prefix + INCOME_ACCOUNT))
                .onInsufficientFunds(enumeration(parameters, prefix + ON_INSUFFICIENT,
                                                 InsufficientFundsPolicy.class,
                                                 InsufficientFundsPolicy.REJECT));

        if (parameters.has(prefix + TAX_ACCOUNT)) {
            builder.taxAccount(parameters.requireUuid(prefix + TAX_ACCOUNT));
        }
        if (parameters.has(prefix + ARREAR_MAX_AGE)) {
            builder.arrearMaxAgeDays(parameters.requireDecimal(prefix + ARREAR_MAX_AGE).intValueExact());
        }
        if (parameters.has(prefix + FLOOR)) {
            builder.floor(Money.of(parameters.requireDecimal(prefix + FLOOR), currency));
        }
        if (parameters.has(prefix + CAP)) {
            builder.cap(Money.of(parameters.requireDecimal(prefix + CAP), currency));
        }
        switch (basis) {
            case FLAT -> builder.flatAmount(
                Money.of(parameters.requireDecimal(prefix + AMOUNT), currency));
            case RATE_ON_CLOSING_BALANCE, RATE_ON_HIGHEST_DEBIT_BALANCE ->
                builder.ratePercent(parameters.requireDecimal(prefix + RATE));
            case TIERED_ON_CLOSING_BALANCE -> builder.tiers(loadTiers(
                c, product, feeCode,
                enumeration(parameters, prefix + TIERING_MODE, TieringMode.class,
                            TieringMode.PROGRESSIVE)));
        }
        return builder.build();
    }

    /**
     * Vrai si le produit fixe lui-meme l'ancrage des periodes.
     *
     * <p>Determine si les conditions peuvent etre partagees par tous les comptes du produit ou
     * doivent etre reancrees compte par compte. La distinction n'a d'effet que sur le cout de
     * resolution, jamais sur les montants.
     */
    public static boolean hasExplicitAnchor(ProductVersion product, String feeCode) {
        return product.parameters().has("fee." + feeCode + "." + ANCHOR);
    }

    /** Code du schema comptable a employer, celui du standard a defaut de designation. */
    public static String schemaCode(ProductVersion product, String feeCode) {
        return product.parameters()
            .optionalString("fee." + feeCode + "." + SCHEMA_CODE, FeeSchemas.STANDARD_CODE);
    }

    /** Decouvert autorise du produit, nul s'il n'est pas parametre. */
    public static Money overdraftLimit(ProductVersion product, CurrencyRef currency) {
        return product.parameters().has(P_OVERDRAFT_LIMIT)
            ? Money.of(product.parameters().requireDecimal(P_OVERDRAFT_LIMIT), currency)
            : Money.zero(currency);
    }

    // ------------------------------------------------------------------ interne

    private static <E extends Enum<E>> E enumeration(ParameterSet parameters, String name,
                                                     Class<E> type, E whenAbsent) {
        return parameters.has(name) ? parameters.requireEnum(name, type) : whenAbsent;
    }

    private static LocalDate anchor(ParameterSet parameters, String name, LocalDate whenAbsent) {
        if (!parameters.has(name)) {
            if (whenAbsent == null) {
                throw new ParameterSet.MissingParameterException(parameters.productCode(), name);
            }
            return whenAbsent;
        }
        String raw = parameters.requireString(name);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ParameterSet.InvalidParameterException(
                parameters.productCode(), name, raw, "une date au format AAAA-MM-JJ");
        }
    }

    private static TieredRate loadTiers(Connection c, ProductVersion product, String feeCode,
                                        TieringMode mode) {
        String purpose = tierPurpose(feeCode);
        List<Tier> tiers = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT from_amount, to_amount, annual_rate_percent FROM product_rate_tier"
            + " WHERE product_version_id = ? AND purpose = ? ORDER BY tier_order")) {
            ps.setObject(1, product.id());
            ps.setString(2, purpose);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tiers.add(new Tier(rs.getBigDecimal(1), rs.getBigDecimal(2),
                                       rs.getBigDecimal(3)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du bareme de la commission " + feeCode, e);
        }
        if (tiers.isEmpty()) {
            throw new ParameterSet.MissingParameterException(
                product.code(), "bareme « " + purpose + " »");
        }
        return new TieredRate(tiers, mode);
    }

    /** Discriminant du bareme d'une commission dans la table des tranches du produit. */
    public static String tierPurpose(String feeCode) {
        return "FEE:" + feeCode;
    }

    /** Enregistre le bareme d'une commission sur une version de produit. */
    public static void setFeeTiers(Connection c, UUID productVersionId, String feeCode,
                                   List<Tier> tiers, TieringMode mode) {
        // Contiguite verifiee a la saisie : un bareme lacunaire est refuse au deploiement du
        // parametrage, pas decouvert au milieu d'un traitement de masse.
        new TieredRate(tiers, mode);
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO product_rate_tier(product_version_id, purpose, tier_order, from_amount,"
            + " to_amount, annual_rate_percent) VALUES (?,?,?,?,?,?)")) {
            for (int i = 0; i < tiers.size(); i++) {
                Tier tier = tiers.get(i);
                ps.setObject(1, productVersionId);
                ps.setString(2, tierPurpose(feeCode));
                ps.setInt(3, i);
                ps.setBigDecimal(4, tier.from());
                ps.setBigDecimal(5, tier.to());
                ps.setBigDecimal(6, tier.annualRatePercent());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du bareme de la commission " + feeCode, e);
        }
    }
}
