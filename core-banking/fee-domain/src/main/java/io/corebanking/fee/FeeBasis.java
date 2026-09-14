package io.corebanking.fee;

/** Assiette sur laquelle la commission est calculee. */
public enum FeeBasis {

    /** Forfait : le montant est le parametre, sans assiette. */
    FLAT(false),

    /** Taux applique au solde constate a la date de perception. */
    RATE_ON_CLOSING_BALANCE(true),

    /**
     * Taux applique au <b>plus fort decouvert</b> de la periode : le plus grand solde debiteur
     * atteint en date de valeur, jour par jour.
     *
     * <p>C'est la commission du plus fort decouvert, percue dans la zone UEMOA comme en France.
     * Deux precisions qui changent le montant :
     *
     * <ul>
     *   <li>le solde retenu est celui <b>en date de valeur</b>, donc apres application des
     *       conditions de banque, et non le solde comptable ;</li>
     *   <li>le taux s'applique <b>directement</b> au pic, sans proratisation temporelle : ce n'est
     *       pas un interet, c'est un pourcentage d'un montant constate. L'annualiser serait une
     *       erreur de nature, pas de reglage.</li>
     * </ul>
     */
    RATE_ON_HIGHEST_DEBIT_BALANCE(true),

    /** Bareme par tranches applique au solde constate a la date de perception. */
    TIERED_ON_CLOSING_BALANCE(true);

    private final boolean needsBalance;

    FeeBasis(boolean needsBalance) {
        this.needsBalance = needsBalance;
    }

    /** Vrai si l'assiette doit etre constatee sur le compte avant calcul. */
    public boolean needsBalance() {
        return needsBalance;
    }
}
