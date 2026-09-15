package io.corebanking.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Requetes gardees telles que recues par la double validation : des cles et des valeurs simples,
 * sans les nulles — une valeur absente est une valeur que l'approbation n'a pas a interpreter.
 */
final class Payloads {

    private Payloads() {}

    /** Paires cle/valeur ; une valeur nulle n'est pas retenue. */
    static Map<String, Object> of(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Paires cle/valeur attendues");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                payload.put(String.valueOf(keyValues[i]), value instanceof Map<?, ?> || value
                    instanceof Number || value instanceof Boolean ? value : value.toString());
            }
        }
        return payload;
    }
}
