package io.corebanking.interest.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Conditions d'interet d'un compte a une journee de valeur.
 *
 * <p>Distinct de {@link InterestTermsResolver}, qui ne connait qu'un compte : sur un lot, la
 * resolution doit etre partagee. Deux millions de comptes rattaches au meme produit ne justifient
 * pas deux millions de lectures de parametrage — c'est une lecture, et un cache.
 */
@FunctionalInterface
public interface TermsProvider {

    InterestTerms termsFor(UUID accountId, LocalDate valueDate);
}
