package io.corebanking.api.usecase;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;

/** Un montant recu en chaine, dans la devise attendue : une autre devise est un refus, jamais une conversion. */
public final class Amounts {

    private Amounts() {}

    public static Money in(String amount, String currency, CurrencyRef expected, String what) {
        if (amount == null || amount.isBlank() || currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Montant et devise obligatoires");
        }
        if (!expected.code().equals(currency)) {
            throw new IllegalArgumentException(
                what + " est tenu en " + expected.code() + ", la requete est en " + currency);
        }
        return Money.of(new BigDecimal(amount), expected);
    }
}
