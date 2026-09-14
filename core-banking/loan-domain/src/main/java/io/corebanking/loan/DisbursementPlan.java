package io.corebanking.loan;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deblocage echelonne : ce que la banque s'engage a mettre a disposition, et quand.
 *
 * <h2>Pourquoi un credit ne se debloque pas toujours en une fois</h2>
 *
 * <p>Un credit de construction, un credit de campagne, un credit d'equipement ne versent pas la
 * totalite a la signature : les fonds suivent l'avancement. Deux consequences que le modele a un
 * seul deblocage ne sait pas porter, et qui ne se rattrapent pas apres coup :
 *
 * <ul>
 *   <li><b>Les interets ne courent que sur le montant mobilise.</b> Faire courir les interets sur
 *       le montant accorde ferait payer a l'emprunteur des fonds qu'il n'a pas recus. C'est le
 *       defaut le plus couteux du contournement habituel — debloquer tout d'un coup sur un compte
 *       d'attente — et il est invisible a la comptabilite, qui reste equilibree.</li>
 *   <li><b>L'echeancier definitif n'existe qu'a la cloture de la mobilisation.</b> Tant qu'une
 *       tranche reste a debloquer, le capital a amortir n'est pas connu. Publier un echeancier
 *       des la signature reviendrait a reclamer l'amortissement d'un capital non verse.</li>
 * </ul>
 *
 * <h2>Ce que le plan garantit</h2>
 *
 * <p>Rangs contigus, dates ordonnees, montants imputables, total egal au montant accorde, et une
 * <b>date limite de mobilisation</b> anterieure a la premiere echeance d'amortissement. Cette
 * derniere regle est la seule qui ferme le modele : debloquer apres le debut de l'amortissement
 * obligerait a rouvrir des echeances deja rendues exigibles, ce que le socle refuse par ailleurs
 * — a juste titre, puisque cela reclamerait deux fois les memes sommes.
 *
 * <p>Le cas ou l'amortissement demarre pendant la mobilisation existe (credits revolving, lignes
 * de tirage) mais releve d'un autre produit : l'echeancier y est recalcule a chaque tirage et
 * n'est jamais contractuel. Il n'est pas couvert ici, et son absence est signalee plutot que
 * contournee.
 */
public record DisbursementPlan(CurrencyRef currency, List<Tranche> tranches,
                               LocalDate drawdownDeadline) {

    public DisbursementPlan {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(drawdownDeadline, "drawdownDeadline");
        tranches = List.copyOf(Objects.requireNonNull(tranches, "tranches"));
        if (tranches.isEmpty()) {
            throw new InvalidDisbursementPlanException(
                "aucune tranche : un credit sans tranche ne se debloque pas");
        }
        LocalDate previous = null;
        for (int index = 0; index < tranches.size(); index++) {
            Tranche tranche = tranches.get(index);
            if (tranche.number() != index + 1) {
                throw new InvalidDisbursementPlanException(
                    "tranche de rang " + tranche.number() + " en position " + (index + 1)
                    + " : les rangs se suivent sans trou, faute de quoi l'ordre de deblocage ne "
                    + "peut plus etre controle");
            }
            if (!tranche.amount().currency().equals(currency)) {
                throw new InvalidDisbursementPlanException(
                    "tranche " + tranche.number() + " libellee en " + tranche.amount().currency()
                    + " dans un plan en " + currency);
            }
            if (previous != null && tranche.plannedOn().isBefore(previous)) {
                throw new InvalidDisbursementPlanException(
                    "tranche " + tranche.number() + " prevue au " + tranche.plannedOn()
                    + ", avant la precedente du " + previous);
            }
            previous = tranche.plannedOn();
        }
        if (drawdownDeadline.isBefore(previous)) {
            throw new InvalidDisbursementPlanException(
                "date limite de mobilisation au " + drawdownDeadline + ", avant la derniere "
                + "tranche prevue au " + previous + " : cette tranche ne pourrait jamais etre "
                + "debloquee");
        }
    }

    /** Montant accorde : la somme des tranches, et donc l'engagement de la banque. */
    public Money committed() {
        Money total = Money.zero(currency);
        for (Tranche tranche : tranches) {
            total = total.plus(tranche.amount());
        }
        return total;
    }

    public Tranche tranche(int number) {
        if (number < 1 || number > tranches.size()) {
            throw new InvalidDisbursementPlanException(
                "tranche " + number + " hors du plan [1.." + tranches.size() + "]");
        }
        return tranches.get(number - 1);
    }

    public int size() {
        return tranches.size();
    }

    /**
     * Confronte le plan aux conditions du credit.
     *
     * <p>Trois desaccords possibles, tous silencieux si on ne les cherche pas : un total de
     * tranches different du capital accorde, une premiere tranche prevue avant la date du contrat,
     * une mobilisation qui deborde sur l'amortissement.
     */
    public void requireConsistentWith(LoanTerms terms) {
        if (!committed().equals(terms.principal())) {
            throw new InvalidDisbursementPlanException(
                "tranches totalisant " + committed() + " pour un credit de " + terms.principal()
                + " : l'ecart serait debloque ou perdu sans que rien ne le signale");
        }
        if (tranches.get(0).plannedOn().isBefore(terms.disbursedOn())) {
            throw new InvalidDisbursementPlanException(
                "premiere tranche prevue au " + tranches.get(0).plannedOn()
                + ", avant la prise d'effet du credit du " + terms.disbursedOn());
        }
        if (!drawdownDeadline.isBefore(terms.firstDueDate().minusDays(1))) {
            throw new InvalidDisbursementPlanException(
                "mobilisation ouverte jusqu'au " + drawdownDeadline + " alors que la premiere "
                + "echeance tombe le " + terms.firstDueDate() + " : une tranche debloquee apres "
                + "cette echeance obligerait a rouvrir des echeances deja reclamees, et une "
                + "mobilisation close la veille ne laisserait aucun jour a la premiere periode "
                + "d'amortissement");
        }
    }

    public static Builder of(CurrencyRef currency) {
        return new Builder(currency);
    }

    /** Assemblage d'un plan, les rangs etant attribues dans l'ordre d'ajout. */
    public static final class Builder {
        private final CurrencyRef currency;
        private final List<Tranche> tranches = new ArrayList<>();
        private LocalDate drawdownDeadline;

        private Builder(CurrencyRef currency) {
            this.currency = currency;
        }

        public Builder tranche(LocalDate plannedOn, Money amount) {
            return tranche(plannedOn, amount, null);
        }

        public Builder tranche(LocalDate plannedOn, Money amount, String condition) {
            tranches.add(new Tranche(tranches.size() + 1, plannedOn, amount, condition));
            return this;
        }

        public Builder deadline(LocalDate value) {
            this.drawdownDeadline = value;
            return this;
        }

        public DisbursementPlan build() {
            return new DisbursementPlan(currency, tranches, drawdownDeadline);
        }
    }
}
