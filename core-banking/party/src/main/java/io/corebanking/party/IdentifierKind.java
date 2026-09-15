package io.corebanking.party;

/**
 * Nature d'un identifiant. Les identifiants <b>officiels</b> designent une seule personne dans
 * l'entite : c'est sur eux que porte le dedoublonnage.
 */
public enum IdentifierKind {
    NATIONAL_ID(true), PASSPORT(true), RESIDENCE_PERMIT(true), TAX_ID(true), TRADE_REGISTRY(true),
    /** Identifiant a la centrale des risques de la banque centrale. */
    CREDIT_BUREAU(true),
    PHONE(false), EMAIL(false);

    private final boolean official;

    IdentifierKind(boolean official) {
        this.official = official;
    }

    public boolean official() {
        return official;
    }
}
