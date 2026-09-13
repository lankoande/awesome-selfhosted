package io.corebanking.interest.service;

import java.time.LocalDate;

/**
 * Resout les conditions d'interet applicables a une <b>journee de valeur donnee</b>.
 *
 * <p>C'est le point qui distingue un moteur rejouable d'un moteur qui ne l'est pas. Resoudre les
 * conditions une seule fois, au lancement du traitement, revient a appliquer le bareme du jour ou
 * l'on calcule aux journees que l'on calcule. Tant qu'aucun bareme ne change, personne ne s'en
 * apercoit. Au premier changement de taux, deux consequences apparaissent ensemble :
 *
 * <ul>
 *   <li>les journees anterieures au changement sont remunerees au nouveau taux ;</li>
 *   <li>un recalcul retroactif ne redonne plus les montants d'origine, et l'arrete cesse d'etre
 *       reproductible.</li>
 * </ul>
 *
 * <p>La resolution par journee supprime les deux.
 */
@FunctionalInterface
public interface InterestTermsResolver {

    InterestTerms termsAt(LocalDate valueDate);

    /**
     * Conditions constantes. Utile pour les tests et pour un produit dont le parametrage n'a jamais
     * varie ; a ne pas employer en production, ou le parametrage vient du catalogue.
     */
    static InterestTermsResolver fixed(InterestTerms terms) {
        return valueDate -> terms;
    }
}
