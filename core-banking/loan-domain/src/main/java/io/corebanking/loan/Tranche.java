package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Une tranche d'un deblocage echelonne.
 *
 * <p>Le <b>montant</b> est celui que la banque s'engage a mettre a disposition, pas celui qu'elle
 * versera necessairement : une tranche se debloque a hauteur de l'avancement constate, et le
 * reliquat tombe avec elle.
 *
 * @param plannedOn date prevue de mise a disposition. Elle ne commande rien a elle seule — c'est le
 *                  constat de la condition qui declenche le deblocage — mais elle ordonne le plan
 *                  et sert de reference au suivi des retards de mobilisation.
 * @param condition fait dont la constatation ouvre le deblocage : « fondations achevees »,
 *                  « proces-verbal de reception ». Conserve en clair et non interprete : la
 *                  constatation est un acte humain, et pretendre l'automatiser reviendrait a
 *                  debloquer des fonds sur la foi d'une date.
 */
public record Tranche(int number, LocalDate plannedOn, Money amount, String condition) {

    public Tranche {
        Objects.requireNonNull(plannedOn, "plannedOn");
        Objects.requireNonNull(amount, "amount");
        if (number < 1) {
            throw new InvalidDisbursementPlanException("tranche de rang " + number
                                                       + " : les rangs partent de 1");
        }
        if (!amount.isPositive()) {
            throw new InvalidDisbursementPlanException(
                "tranche " + number + " d'un montant de " + amount
                + " : une tranche sans montant n'engage rien et masquerait une condition oubliee");
        }
        if (!amount.isBookable()) {
            throw new InvalidDisbursementPlanException(
                "tranche " + number + " : " + amount + " n'est pas imputable en "
                + amount.currency());
        }
        condition = condition == null || condition.isBlank() ? null : condition.strip();
    }

    public static Tranche of(int number, LocalDate plannedOn, Money amount) {
        return new Tranche(number, plannedOn, amount, null);
    }

    public static Tranche of(int number, LocalDate plannedOn, Money amount, String condition) {
        return new Tranche(number, plannedOn, amount, condition);
    }
}
