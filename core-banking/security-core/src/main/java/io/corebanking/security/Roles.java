package io.corebanking.security;

/**
 * Roles applicatifs. Ils correspondent aux roles de royaume Keycloak et sont la seule chose que le
 * fournisseur d'identite transmet en matiere d'habilitation.
 */
public final class Roles {

    public static final String TELLER           = "teller";
    public static final String BRANCH_MANAGER   = "branch_manager";
    public static final String CUSTOMER_OFFICER = "customer_officer";
    public static final String CREDIT_OFFICER   = "credit_officer";
    public static final String CREDIT_MANAGER   = "credit_manager";
    public static final String ACCOUNTANT       = "accountant";
    public static final String PRODUCT_MANAGER  = "product_manager";
    public static final String RISK_OFFICER     = "risk_officer";
    public static final String OPERATOR         = "operator";
    public static final String AUDITOR          = "auditor";

    private Roles() {}
}
