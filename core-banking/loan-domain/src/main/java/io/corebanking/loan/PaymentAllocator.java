package io.corebanking.loan;

import io.corebanking.kernel.money.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Imputation d'un reglement sur les creances d'un credit.
 *
 * <h2>Les trois regles, et ce qu'elles coutent quand on les inverse</h2>
 *
 * <ol>
 *   <li><b>L'ordre des categories vient du parametrage</b>, jamais du code. Il a un effet financier
 *       direct et plusieurs juridictions l'imposent.</li>
 *   <li><b>Au sein d'une categorie, la creance la plus ancienne d'abord.</b> C'est elle qui compte
 *       les jours de retard, et donc elle qui declasse le credit et declenche le provisionnement.
 *       Solder la plus recente laisserait le compteur courir sur la plus ancienne alors meme que le
 *       client paie.</li>
 *   <li><b>L'imputation partielle est admise.</b> Une echeance de credit est une dette qui
 *       s'amortit : un reglement de la moitie la reduit de moitie. C'est l'inverse de la regle
 *       retenue pour les commissions, ou un encaissement partiel scinderait une assiette taxable
 *       deja declaree — la difference de nature justifie la difference de traitement.</li>
 * </ol>
 *
 * <p>Le solde non impute est restitue a l'appelant plutot que consomme : c'est a lui de decider
 * s'il constitue un remboursement anticipe, un avoir sur le compte, ou un rejet. La decision
 * depend du canal et du produit, pas de l'arithmetique.
 */
public final class PaymentAllocator {

    private PaymentAllocator() {}

    /** Resultat d'une imputation. */
    public record Result(Money payment, List<Allocation> allocations, Money unallocated) {

        public Result {
            allocations = List.copyOf(allocations);
        }

        /** Total effectivement impute. */
        public Money allocated() {
            return payment.minus(unallocated);
        }

        /** Creances entierement soldees par ce reglement. */
        public List<Allocation> settled() {
            return allocations.stream().filter(Allocation::settles).toList();
        }
    }

    public static Result allocate(Money payment, List<Receivable> receivables,
                                  AllocationOrder order) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(order, "order");
        if (!payment.isPositive()) {
            throw new IllegalArgumentException(
                "Reglement de " + payment + " : un reglement nul ou negatif n'est pas un reglement.");
        }
        if (!payment.isBookable()) {
            throw new IllegalArgumentException(
                "Reglement de " + payment + " non imputable en " + payment.currency()
                + " : un montant encaisse est toujours un montant imputable.");
        }

        List<Allocation> allocations = new ArrayList<>();
        Money remaining = payment;

        for (DueCategory category : order.order()) {
            List<Receivable> ofCategory = receivables.stream()
                .filter(receivable -> receivable.category() == category)
                .sorted(Comparator.comparing(Receivable::dueDate)
                            .thenComparingInt(Receivable::instalmentNumber))
                .toList();

            for (Receivable receivable : ofCategory) {
                if (!remaining.isPositive()) {
                    return new Result(payment, allocations, remaining);
                }
                requireSameCurrency(payment, receivable);
                Money amount = remaining.isLessThan(receivable.outstanding())
                    ? remaining : receivable.outstanding();
                allocations.add(new Allocation(receivable, amount, null));
                remaining = remaining.minus(amount);
            }
        }
        return new Result(payment, allocations, remaining);
    }

    private static void requireSameCurrency(Money payment, Receivable receivable) {
        if (!receivable.outstanding().currency().equals(payment.currency())) {
            throw new IllegalArgumentException(
                "Creance en " + receivable.outstanding().currency() + " reglee en "
                + payment.currency() + " : la conversion est une operation de change, elle ne se "
                + "fait pas au detour d'une imputation.");
        }
    }
}
