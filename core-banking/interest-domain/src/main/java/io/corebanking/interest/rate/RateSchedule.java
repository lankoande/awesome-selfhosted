package io.corebanking.interest.rate;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;

/**
 * Bareme de taux applique a un solde.
 *
 * <p>Le bareme calcule lui-meme le montant plutot que de renvoyer un taux : un bareme par tranches
 * progressives n'a pas de taux unique, et le reduire a un « taux moyen » puis multiplier introduit
 * une erreur d'arrondi la ou l'addition des tranches est exacte.
 */
public interface RateSchedule {

    /** Interet courant pour un solde et une fraction d'annee donnes, en precision interne. */
    Money accrue(Money balance, BigDecimal yearFraction);

    /**
     * Taux effectif constate pour ce solde, a des fins de restitution et de controle uniquement.
     * Il n'entre dans aucun calcul.
     */
    BigDecimal effectiveRate(Money balance);
}
