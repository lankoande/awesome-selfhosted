package io.corebanking.loan;

/**
 * Methode de passage du taux periodique au taux annuel effectif.
 *
 * <p>Les deux methodes sont licites, elles ne donnent pas le meme chiffre, et le regulateur en
 * impose une. Sur un credit mensuel a 1 % par mois, la methode proportionnelle annonce 12 % et la
 * methode actuarielle 12,68 % — pour exactement les memes flux. Presenter l'un pour l'autre n'est
 * pas une approximation, c'est une erreur de declaration.
 */
public enum RateAnnualisation {

    /**
     * Taux periodique multiplie par le nombre de periodes. Convention historique du taux effectif
     * global francais, et celle de plusieurs textes de la zone.
     */
    PROPORTIONAL,

    /**
     * Taux equivalent : {@code (1 + i)^n - 1}. Convention du taux annuel effectif global europeen.
     * Elle tient compte de la capitalisation infra-annuelle, et donne donc toujours un chiffre
     * superieur ou egal au proportionnel.
     */
    ACTUARIAL
}
