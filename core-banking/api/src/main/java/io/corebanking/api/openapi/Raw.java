package io.corebanking.api.openapi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reponse servie telle quelle, hors enveloppe : le contrat OpenAPI lui-meme. Tout le reste de
 * l'API sort dans {@code ApiResponse} ; ce marqueur est la seule exception, et il se voit.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Raw {
}
