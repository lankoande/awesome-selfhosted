package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Echeancier complet d'un credit.
 *
 * <p>Les invariants sont verifies a la construction, ce qui rend un echeancier faux
 * <b>impossible a representer</b>. Ce n'est pas de la defiance envers le generateur : un
 * echeancier arrive aussi par reprise de donnees, par import d'un systeme tiers, ou par
 * rechelonnement saisi a la main. Les trois chemins passent ici.
 */
public record AmortisationSchedule(LoanTerms terms, List<Instalment> instalments) {

    public AmortisationSchedule {
        Objects.requireNonNull(terms, "terms");
        instalments = List.copyOf(Objects.requireNonNull(instalments, "instalments"));

        if (instalments.size() != terms.instalmentCount()) {
            throw new InvalidScheduleException(
                instalments.size() + " echeances pour un credit qui en compte "
                + terms.instalmentCount());
        }

        Money repaid = Money.zero(terms.currency());
        Money expectedOutstanding = terms.principal();
        LocalDate previousEnd = null;
        for (int index = 0; index < instalments.size(); index++) {
            Instalment instalment = instalments.get(index);
            if (instalment.number() != index + 1) {
                throw new InvalidScheduleException(
                    "echeances numerotees " + instalment.number() + " en position " + (index + 1));
            }
            if (!instalment.outstandingBefore().equals(expectedOutstanding)) {
                throw new InvalidScheduleException(
                    "echeance " + instalment.number() + " : capital restant du a l'ouverture "
                    + instalment.outstandingBefore() + " au lieu de " + expectedOutstanding);
            }
            // Periodes jointives : aucune journee d'interet comptee deux fois ni oubliee.
            if (previousEnd != null && !instalment.periodStart().equals(previousEnd.plusDays(1))) {
                throw new InvalidScheduleException(
                    "echeance " + instalment.number() + " : la periode d'interet s'ouvre le "
                    + instalment.periodStart() + " alors que la precedente s'est fermee le "
                    + previousEnd);
            }
            repaid = repaid.plus(instalment.principal());
            expectedOutstanding = instalment.outstandingAfter();
            previousEnd = instalment.periodEnd();
        }

        // L'invariant qui compte. Un echeancier dont la somme des capitaux ne redonne pas le
        // capital emprunte laisse un solde residuel a la derniere echeance : le client croit avoir
        // solde, et le systeme lui reclame quelques francs des annees plus tard.
        if (!repaid.equals(terms.principal())) {
            throw new InvalidScheduleException(
                "la somme des capitaux amortis vaut " + repaid + " pour un capital emprunte de "
                + terms.principal() + " : ecart de " + terms.principal().minus(repaid));
        }
        if (!expectedOutstanding.isZero()) {
            throw new InvalidScheduleException(
                "capital restant du de " + expectedOutstanding + " apres la derniere echeance");
        }
    }

    public Instalment instalment(int number) {
        return instalments.get(number - 1);
    }

    public Instalment last() {
        return instalments.get(instalments.size() - 1);
    }

    /** Somme des interets de l'echeancier : le cout du credit hors assurance, frais et taxes. */
    public Money totalInterest() {
        return sum(Instalment::interest);
    }

    public Money totalInsurance() {
        return sum(Instalment::insurance);
    }

    public Money totalTax() {
        return sum(Instalment::tax);
    }

    /** Somme des echeances : ce que le credit coute reellement a l'emprunteur. */
    public Money totalRepaid() {
        return sum(Instalment::total);
    }

    private Money sum(java.util.function.Function<Instalment, Money> component) {
        Money total = Money.zero(terms.currency());
        for (Instalment instalment : instalments) {
            total = total.plus(component.apply(instalment));
        }
        return total;
    }

    /** Echeancier refuse : il ne peut pas exister. */
    public static class InvalidScheduleException extends RuntimeException {
        public InvalidScheduleException(String detail) {
            super("Echeancier incoherent : " + detail + ".");
        }
    }
}
