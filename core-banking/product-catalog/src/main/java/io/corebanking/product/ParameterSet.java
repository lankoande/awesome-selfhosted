package io.corebanking.product;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Parametres d'une version de produit, lus de facon typee.
 *
 * <p>Un parametre absent ou mal forme leve une erreur explicite nommant le produit et le parametre.
 * C'est volontaire : ce type d'erreur doit tomber au deploiement du parametrage ou au demarrage
 * d'un TFJ a blanc, pas au milieu d'un traitement de masse sur un compte quelconque.
 */
public record ParameterSet(String productCode, Map<String, String> values) {

    public ParameterSet {
        Objects.requireNonNull(productCode, "productCode");
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public String requireString(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new MissingParameterException(productCode, name);
        }
        return value;
    }

    public BigDecimal requireDecimal(String name) {
        String raw = requireString(name);
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new InvalidParameterException(productCode, name, raw, "nombre decimal");
        }
    }

    public UUID requireUuid(String name) {
        String raw = requireString(name);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException(productCode, name, raw, "identifiant UUID");
        }
    }

    public <E extends Enum<E>> E requireEnum(String name, Class<E> type) {
        String raw = requireString(name);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterException(productCode, name, raw,
                "une valeur de " + type.getSimpleName());
        }
    }

    public String optionalString(String name, String fallback) {
        String value = values.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** Parametre exige par le traitement et absent du parametrage. */
    public static class MissingParameterException extends RuntimeException {
        public MissingParameterException(String productCode, String name) {
            super("Parametre « " + name + " » absent du produit " + productCode
                  + ". Le parametrage est incomplet : corriger avant traitement.");
        }
    }

    /** Parametre present mais inexploitable. */
    public static class InvalidParameterException extends RuntimeException {
        public InvalidParameterException(String productCode, String name, String value,
                                         String expected) {
            super("Parametre « " + name + " » du produit " + productCode + " : « " + value
                  + " » n'est pas " + expected + ".");
        }
    }
}
