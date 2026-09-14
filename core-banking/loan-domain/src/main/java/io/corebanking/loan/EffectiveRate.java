package io.corebanking.loan;

import io.corebanking.interest.rate.MathContexts;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Taux effectif global : le taux qui annule la valeur actuelle des flux du credit.
 *
 * <h2>Pourquoi il ne se deduit pas du taux nominal</h2>
 *
 * <p>Le taux nominal ne dit rien du cout du credit. Des frais de dossier preleves au deblocage
 * reduisent la somme reellement recue sans reduire ce qui est rembourse ; une assurance, une taxe,
 * un differe deplacent les flux. Le taux effectif est le seul chiffre comparable d'un credit a
 * l'autre, et c'est lui que la reglementation plafonne.
 *
 * <h2>La resolution : dichotomie, pas Newton</h2>
 *
 * <p>La fonction a annuler est strictement croissante en {@code i} des lors que l'emprunteur
 * rembourse plus qu'il n'a recu : la dichotomie converge donc toujours, en un nombre d'iterations
 * connu d'avance. Newton converge plus vite mais peut diverger sur un echeancier degenere — une
 * premiere echeance tres proche du deblocage, un flux nul — et un moteur de calcul reglementaire
 * ne peut pas se permettre un cas ou il ne rend pas de reponse.
 *
 * <p>L'intervalle de recherche est <b>elargi tant que la racine n'y est pas</b>, plutot que fixe a
 * l'avance : un credit a taux tres eleve, ou au contraire bonifie et donc a taux negatif, doit
 * etre chiffre et non refuse. La borne basse s'arrete a {@code -99 %}, en deca de quoi le
 * denominateur s'annule.
 */
public final class EffectiveRate {

    /** Precision de la resolution, tres au-dela de ce qui est restitue. */
    private static final BigDecimal TOLERANCE = new BigDecimal("1E-14");

    /** Nombre d'iterations de dichotomie. Chacune divise l'intervalle par deux. */
    private static final int ITERATIONS = 200;

    private static final int MAX_BRACKET_DOUBLINGS = 60;

    private static final BigDecimal FLOOR = new BigDecimal("-0.99");

    private static final MathContext CONTEXT = MathContexts.RATE;

    /** Echelle de restitution du taux, en pourcentage. */
    private static final int RATE_SCALE = 6;

    private EffectiveRate() {}

    /**
     * Taux effectif d'un echeancier.
     *
     * @param upfrontFees frais preleves au deblocage : ils ne sont pas rembourses par
     *                    l'emprunteur, ils lui sont retenus, et c'est ce qui les rend si couteux
     *                    en taux effectif
     */
    public static Teg of(AmortisationSchedule schedule, Money upfrontFees,
                         RateAnnualisation method) {
        LoanTerms terms = schedule.terms();
        Money fees = upfrontFees == null ? Money.zero(terms.currency()) : upfrontFees;
        if (fees.isNegative()) {
            throw new IllegalArgumentException("Frais de deblocage negatifs : " + fees);
        }
        Money received = terms.principal().minus(fees);
        if (!received.isPositive()) {
            throw new IllegalArgumentException(
                "Les frais preleves au deblocage (" + fees + ") absorbent le capital ("
                + terms.principal() + ") : l'emprunteur ne recoit rien.");
        }

        List<TimedFlow> flows = new ArrayList<>(schedule.instalments().size());
        Money repaid = Money.zero(terms.currency());
        for (Instalment instalment : schedule.instalments()) {
            flows.add(new TimedFlow(periodsBetween(terms, instalment), instalment.total()));
            repaid = repaid.plus(instalment.total());
        }

        BigDecimal periodic = periodicRate(received, flows);
        BigDecimal annual = annualise(periodic, terms.frequency().periodsPerYear(), method);
        return new Teg(percent(periodic), percent(annual), method, received, repaid);
    }

    /**
     * Taux effectif d'un credit <b>debloque par tranches</b>.
     *
     * <h2>Pourquoi il ne se calcule pas sur l'echeancier seul</h2>
     *
     * <p>Une tranche versee six mois apres la signature ne vaut pas, pour l'emprunteur, une tranche
     * versee le jour meme : il n'en a pas dispose pendant six mois. Faire comme s'il avait tout
     * recu a l'origine lui prete une somme qu'il n'avait pas, et <b>sous-estime</b> le taux
     * effectif. Le sens de l'erreur importe : c'est celui qui fait passer sous le plafond d'usure
     * un credit qui le depasse.
     *
     * <p>Les deux cotes sont donc actualises : ce que l'emprunteur recoit, tranche par tranche, et
     * ce qu'il paie — frais retenus, interets intercalaires, echeances. L'origine des temps est la
     * premiere mise a disposition, la seule date a laquelle l'emprunteur n'a encore rien recu.
     *
     * @param origin    date de la premiere mise a disposition
     * @param frequency periodicite du credit, qui donne l'unite de l'axe des temps
     */
    public static Teg between(LocalDate origin, Periodicity frequency, List<DatedFlow> received,
                              List<DatedFlow> paid, RateAnnualisation method) {
        if (received.isEmpty()) {
            throw new IllegalArgumentException(
                "Aucune mise a disposition : un credit non mobilise n'a pas de taux effectif.");
        }
        List<TimedFlow> in = timed(received, origin, frequency);
        List<TimedFlow> out = timed(paid, origin, frequency);
        BigDecimal periodic = periodicRate(in, out);
        BigDecimal annual = annualise(periodic, frequency.periodsPerYear(), method);
        return new Teg(percent(periodic), percent(annual), method, total(received), total(paid));
    }

    private static List<TimedFlow> timed(List<DatedFlow> flows, LocalDate origin,
                                         Periodicity frequency) {
        List<TimedFlow> timed = new ArrayList<>(flows.size());
        for (DatedFlow flow : flows) {
            if (flow.on().isBefore(origin)) {
                throw new IllegalArgumentException(
                    "Flux au " + flow.on() + ", anterieur a l'origine du " + origin + ".");
            }
            timed.add(new TimedFlow(periods(origin, flow.on(), frequency), flow.amount()));
        }
        return timed;
    }

    private static Money total(List<DatedFlow> flows) {
        Money total = flows.get(0).amount();
        for (int index = 1; index < flows.size(); index++) {
            total = total.plus(flows.get(index).amount());
        }
        return total;
    }

    /** Position d'une date sur l'axe des periodes, comptee en jours reels. */
    private static BigDecimal periods(LocalDate origin, LocalDate date, Periodicity frequency) {
        BigDecimal daysPerPeriod = BigDecimal.valueOf(365)
            .divide(BigDecimal.valueOf(frequency.periodsPerYear()), CONTEXT);
        return BigDecimal.valueOf(ChronoUnit.DAYS.between(origin, date))
            .divide(daysPerPeriod, CONTEXT);
    }

    /**
     * Taux periodique annulant la valeur actuelle.
     *
     * @param received somme mise a disposition a l'origine
     * @param flows    remboursements, dates en periodes
     */
    public static BigDecimal periodicRate(Money received, List<TimedFlow> flows) {
        return periodicRate(List.of(new TimedFlow(BigDecimal.ZERO, received)), flows);
    }

    /**
     * Taux periodique annulant la valeur actuelle, les deux cotes etant dates.
     *
     * <p>La monotonie qui rend la dichotomie sure tient tant que les sommes recues precedent les
     * sommes payees — ce qui est le cas de tout credit. Un montage ou l'emprunteur paierait avant
     * de recevoir n'est pas un credit et son taux n'aurait pas de sens.
     */
    public static BigDecimal periodicRate(List<TimedFlow> received, List<TimedFlow> flows) {
        if (flows.isEmpty()) {
            throw new IllegalArgumentException("Aucun flux de remboursement : taux indefini.");
        }
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = BigDecimal.ZERO;

        // Le credit coute-t-il quelque chose ? Si oui la racine est positive, sinon negative.
        BigDecimal atZero = presentValueGap(received, flows, BigDecimal.ZERO);
        if (atZero.abs().compareTo(TOLERANCE) <= 0) {
            return BigDecimal.ZERO;
        }
        if (atZero.signum() < 0) {
            high = new BigDecimal("0.01");
            for (int i = 0; i < MAX_BRACKET_DOUBLINGS
                            && presentValueGap(received, flows, high).signum() < 0; i++) {
                high = high.multiply(BigDecimal.TWO);
            }
            if (presentValueGap(received, flows, high).signum() < 0) {
                throw new IllegalStateException(
                    "Taux effectif hors de toute borne raisonnable : verifier l'echeancier.");
            }
        } else {
            low = new BigDecimal("-0.01");
            for (int i = 0; i < MAX_BRACKET_DOUBLINGS
                            && presentValueGap(received, flows, low).signum() > 0; i++) {
                low = low.multiply(BigDecimal.TWO).max(FLOOR);
                if (low.compareTo(FLOOR) == 0) {
                    break;
                }
            }
        }

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            BigDecimal middle = low.add(high).divide(BigDecimal.TWO, CONTEXT);
            BigDecimal gap = presentValueGap(received, flows, middle);
            if (gap.abs().compareTo(TOLERANCE) <= 0
                || high.subtract(low).compareTo(TOLERANCE) <= 0) {
                return middle;
            }
            if (gap.signum() < 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low.add(high).divide(BigDecimal.TWO, CONTEXT);
    }

    /**
     * Ecart entre ce qui est recu et la valeur actuelle de ce qui est rembourse.
     *
     * <p>Negatif tant que le taux d'actualisation est trop faible — les remboursements pesent plus
     * que ce qui a ete recu — et croissant avec lui. C'est cette monotonie qui rend la dichotomie
     * sure.
     */
    private static BigDecimal presentValueGap(List<TimedFlow> received, List<TimedFlow> flows,
                                              BigDecimal rate) {
        BigDecimal base = BigDecimal.ONE.add(rate);
        BigDecimal value = BigDecimal.ZERO;
        for (TimedFlow flow : received) {
            value = value.add(flow.amount().amount().divide(power(base, flow.periods()), CONTEXT));
        }
        for (TimedFlow flow : flows) {
            value = value.subtract(flow.amount().amount().divide(power(base, flow.periods()),
                                                                 CONTEXT));
        }
        return value;
    }

    /**
     * Puissance a exposant fractionnaire.
     *
     * <p>{@code BigDecimal} ne l'offre pas ; le passage par {@code double} est ici sans
     * consequence parce qu'il ne sert qu'a <b>situer</b> la racine, jamais a produire un montant.
     * Les montants, eux, sortent de l'echeancier, qui est entier et exact.
     */
    private static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        if (exponent.stripTrailingZeros().scale() <= 0) {
            return base.pow(exponent.intValueExact(), CONTEXT);
        }
        return BigDecimal.valueOf(Math.pow(base.doubleValue(), exponent.doubleValue()));
    }

    /** Passage du taux periodique au taux annuel, selon la convention retenue. */
    public static BigDecimal annualise(BigDecimal periodic, int periodsPerYear,
                                       RateAnnualisation method) {
        return switch (method) {
            case PROPORTIONAL -> periodic.multiply(BigDecimal.valueOf(periodsPerYear), CONTEXT);
            case ACTUARIAL -> BigDecimal.valueOf(
                Math.pow(1 + periodic.doubleValue(), periodsPerYear) - 1);
        };
    }

    /**
     * Position d'une echeance sur l'axe des periodes.
     *
     * <p>Comptee en <b>jours reels</b> rapportes a la duree moyenne d'une periode, et non par le
     * rang de l'echeance. Deux consequences mesurables :
     *
     * <ul>
     *   <li>un echeancier mensuel regulier n'affiche pas exactement le taux nominal — les mois de
     *       trente et un jours ne durent pas 365/12 jours, et l'ecart se voit a la troisieme
     *       decimale ;</li>
     *   <li>un differe de premiere echeance <b>abaisse</b> le taux effectif sur une annuite a taux
     *       periodique proportionnel : l'emprunteur garde les fonds plus longtemps sans payer
     *       davantage. Le cout du differe est supporte par la banque, et le taux effectif le dit.</li>
     * </ul>
     */
    private static BigDecimal periodsBetween(LoanTerms terms, Instalment instalment) {
        long days = ChronoUnit.DAYS.between(terms.disbursedOn(), instalment.dueDate());
        BigDecimal daysPerPeriod = BigDecimal.valueOf(365)
            .divide(BigDecimal.valueOf(terms.frequency().periodsPerYear()), CONTEXT);
        return BigDecimal.valueOf(days).divide(daysPerPeriod, CONTEXT);
    }

    private static BigDecimal percent(BigDecimal rate) {
        return rate.movePointRight(2).setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }
}
