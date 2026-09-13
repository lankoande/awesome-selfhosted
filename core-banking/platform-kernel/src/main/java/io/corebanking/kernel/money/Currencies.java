package io.corebanking.kernel.money;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registre des devises. En production il est alimente depuis le referentiel ; ces constantes
 * servent d'amorcage et de support de test.
 *
 * <p>XOF et XAF sont deux devises distinctes. Elles partagent la meme parite fixe avec l'euro
 * mais relevent de deux unions monetaires differentes (UEMOA et CEMAC) et ne sont pas
 * interchangeables. Les confondre est une erreur classique des systemes concus hors zone.
 */
public final class Currencies {

    public static final CurrencyRef XOF = CurrencyRef.of("XOF", 0);
    public static final CurrencyRef XAF = CurrencyRef.of("XAF", 0);
    public static final CurrencyRef EUR = CurrencyRef.of("EUR", 2);
    public static final CurrencyRef USD = CurrencyRef.of("USD", 2);
    public static final CurrencyRef TND = CurrencyRef.of("TND", 3);

    private static final Map<String, CurrencyRef> BUILT_IN = new LinkedHashMap<>();

    static {
        for (CurrencyRef c : new CurrencyRef[] {XOF, XAF, EUR, USD, TND}) {
            BUILT_IN.put(c.code(), c);
        }
    }

    private Currencies() {}

    public static Optional<CurrencyRef> find(String code) {
        return Optional.ofNullable(BUILT_IN.get(code));
    }

    public static CurrencyRef require(String code) {
        return find(code).orElseThrow(
            () -> new IllegalArgumentException("Devise inconnue du registre : " + code));
    }
}
