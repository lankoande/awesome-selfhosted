package io.corebanking.product;

import java.time.LocalDate;

/**
 * Aucune version active d'un produit a une date donnee.
 *
 * <p>Refus explicite plutot que repli sur la version la plus recente : appliquer un bareme qui
 * n'etait pas en vigueur produit des montants faux que rien ne signale ensuite.
 */
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String code, LocalDate date) {
        super("Aucune version active du produit " + code + " au " + date
              + ". Un traitement ne peut pas se rabattre sur une autre version : "
              + "le parametrage doit couvrir toute la periode traitee.");
    }
}
