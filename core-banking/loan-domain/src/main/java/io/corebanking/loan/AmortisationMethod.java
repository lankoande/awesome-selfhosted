package io.corebanking.loan;

/**
 * Methode d'amortissement d'un credit.
 *
 * <p>Chaque methode porte <b>sa propre convention de taux</b>, et ce n'est pas un reglage
 * supplementaire : c'est ce qui definit la methode.
 */
public enum AmortisationMethod {

    /**
     * Annuites constantes : l'echeance ne varie pas, la part de capital croit, celle d'interet
     * decroit.
     *
     * <p>L'interet se calcule au <b>taux periodique proportionnel</b> — le taux annuel divise par
     * le nombre de periodes — et non sur les jours reellement ecoules. C'est la definition de la
     * methode : un interet assis sur des mois de 28 a 31 jours ne produirait pas une echeance
     * constante, et l'appeler « annuite constante » serait alors un abus de langage. L'ecart avec
     * le taux actuariel equivalent est connu et assume.
     */
    CONSTANT_ANNUITY(true),

    /**
     * Amortissement constant du capital : l'echeance decroit, la part de capital ne varie pas.
     *
     * <p>L'interet se calcule sur les <b>jours reellement ecoules</b>, selon la convention du
     * contrat. Rien n'impose ici de lisser quoi que ce soit, et la fidelite au calendrier est
     * preferable : l'interet d'une periode egale alors exactement la somme des interets courus
     * quotidiens de cette periode.
     */
    CONSTANT_PRINCIPAL(false),

    /**
     * In fine : le capital est rembourse en une fois a la derniere echeance, les interets sont
     * servis periodiquement sur les jours reellement ecoules.
     */
    BULLET(false);

    private final boolean proportionalPeriodicRate;

    AmortisationMethod(boolean proportionalPeriodicRate) {
        this.proportionalPeriodicRate = proportionalPeriodicRate;
    }

    /** Vrai si l'interet se calcule au taux periodique proportionnel plutot qu'aux jours ecoules. */
    public boolean usesProportionalPeriodicRate() {
        return proportionalPeriodicRate;
    }
}
