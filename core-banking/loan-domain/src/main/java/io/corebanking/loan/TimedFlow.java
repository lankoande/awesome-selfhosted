package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Flux de tresorerie date, exprime en periodes depuis l'origine du credit.
 *
 * <p>Le temps est compte en <b>periodes</b> et non en jours : c'est la grandeur dans laquelle le
 * taux periodique se resout, et elle admet les valeurs fractionnaires. Une premiere echeance a
 * quarante-cinq jours sur un credit mensuel se situe a environ {@code 1,48} periode, et le taux
 * effectif s'en trouve change.
 *
 * @param periods instant du flux, en periodes depuis le deblocage
 * @param amount  montant paye par l'emprunteur ; le deblocage est le seul flux recu
 */
public record TimedFlow(BigDecimal periods, Money amount) {

    public TimedFlow {
        Objects.requireNonNull(periods, "periods");
        Objects.requireNonNull(amount, "amount");
        if (periods.signum() < 0) {
            throw new IllegalArgumentException("Flux anterieur au deblocage : " + periods);
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                "Flux negatif " + amount + " : le sens d'un flux est porte par sa place dans la "
                + "sequence, jamais par son signe.");
        }
    }
}
