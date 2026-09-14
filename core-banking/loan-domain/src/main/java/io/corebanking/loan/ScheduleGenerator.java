package io.corebanking.loan;

import io.corebanking.interest.rate.MathContexts;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Construction de l'echeancier a partir des conditions du credit.
 *
 * <h2>L'arrondi, et pourquoi la derniere echeance n'est pas comme les autres</h2>
 *
 * <p>En devise sans subdivision, une annuite calculee a 148 527,84 XOF s'impute a 148 528. Repetee
 * soixante fois, cette unite d'ecart se cumule : la somme des capitaux amortis ne redonne pas le
 * capital emprunte, et il reste au client un solde residuel de quelques francs apres sa derniere
 * echeance. Le defaut est invisible a la lecture de l'echeancier et se manifeste des annees plus
 * tard, sous la forme d'une relance pour un montant que personne ne sait expliquer.
 *
 * <p>La regle retenue est la seule qui ferme exactement : <b>la derniere echeance solde le capital
 * restant du</b>, quel qu'il soit. Son montant total peut donc differer de quelques unites des
 * precedentes. C'est visible, c'est explicable au client, et c'est verifie par la construction meme
 * de {@link AmortisationSchedule}.
 *
 * <h2>Les interets, selon la methode</h2>
 *
 * <p>A annuites constantes, l'interet se calcule au taux periodique proportionnel — sans quoi
 * l'echeance ne serait pas constante. Dans les autres methodes, il se calcule sur les jours
 * reellement ecoules, et l'interet d'une periode egale alors <b>exactement</b> la somme des
 * interets courus quotidiens de cette periode, telle que la calcule le moteur d'accruals.
 */
public final class ScheduleGenerator {

    private ScheduleGenerator() {}

    public static AmortisationSchedule generate(LoanTerms terms) {
        List<Instalment> instalments = new ArrayList<>(terms.instalmentCount());
        Money outstanding = terms.principal();
        Money annuity = terms.method() == AmortisationMethod.CONSTANT_ANNUITY
            ? constantAnnuity(terms) : null;
        Money constantPrincipal = terms.method() == AmortisationMethod.CONSTANT_PRINCIPAL
            ? terms.principal().dividedBy(BigDecimal.valueOf(terms.amortisingCount()))
                  .roundToCurrency()
            : null;

        LocalDate periodStart = terms.disbursedOn();
        for (int number = 1; number <= terms.instalmentCount(); number++) {
            LocalDate dueDate = terms.dueDate(number);
            boolean last = number == terms.instalmentCount();
            boolean amortising = number > terms.graceInstalments();

            Money interest = interestFor(terms, outstanding, periodStart, dueDate);
            Money principal = principalFor(terms, outstanding, annuity, constantPrincipal, interest,
                                           amortising, last);
            Money insurance = insuranceFor(terms, outstanding);
            Money tax = interest.times(terms.taxOnInterestRatePercent().movePointLeft(2))
                                .roundToCurrency();
            Money total = principal.plus(interest).plus(insurance).plus(terms.periodicFee())
                                   .plus(tax);

            instalments.add(new Instalment(number, dueDate, periodStart, dueDate, outstanding,
                                           principal, interest, insurance, terms.periodicFee(), tax,
                                           total, outstanding.minus(principal)));
            outstanding = outstanding.minus(principal);
            periodStart = dueDate.plusDays(1);
        }
        return new AmortisationSchedule(terms, instalments);
    }

    // ------------------------------------------------------------------ composantes

    private static Money interestFor(LoanTerms terms, Money outstanding, LocalDate periodStart,
                                     LocalDate periodEnd) {
        if (terms.method().usesProportionalPeriodicRate()) {
            return outstanding.times(terms.periodicRate()).roundToCurrency();
        }
        // Borne de fin exclue dans la convention de decompte : la periode couvre periodStart
        // jusqu'a la date d'echeance incluse, exactement comme la serie d'interets courus.
        BigDecimal fraction = terms.dayCount().yearFraction(periodStart, periodEnd.plusDays(1));
        return outstanding.times(terms.annualRatePercent().movePointLeft(2))
                          .times(fraction).roundToCurrency();
    }

    private static Money principalFor(LoanTerms terms, Money outstanding, Money annuity,
                                      Money constantPrincipal, Money interest, boolean amortising,
                                      boolean last) {
        if (!amortising) {
            // Differe d'amortissement : seuls les interets sont servis. Le capital est intact, et
            // l'echeancier compte assez d'echeances apres le differe pour l'amortir — refuse a la
            // construction des conditions dans le cas contraire.
            return Money.zero(terms.currency());
        }
        if (last) {
            // La derniere echeance solde. C'est ici que l'ecart d'arrondi cumule est absorbe.
            return outstanding;
        }
        Money principal = switch (terms.method()) {
            case CONSTANT_ANNUITY -> annuity.minus(interest);
            case CONSTANT_PRINCIPAL -> constantPrincipal;
            case BULLET -> Money.zero(terms.currency());
        };
        return principal.isGreaterThan(outstanding) ? outstanding : principal;
    }

    private static Money insuranceFor(LoanTerms terms, Money outstanding) {
        BigDecimal rate = terms.insuranceRatePercent().movePointLeft(2);
        return switch (terms.insuranceBasis()) {
            case NONE -> Money.zero(terms.currency());
            case INITIAL_PRINCIPAL -> terms.principal().times(rate).roundToCurrency();
            case OUTSTANDING_PRINCIPAL -> outstanding.times(rate).roundToCurrency();
        };
    }

    // ------------------------------------------------------------------ annuite

    /**
     * Annuite constante : {@code A = P x i / (1 - (1+i)^-m)}, arrondie a l'echelle de la devise.
     *
     * <p>Le refus qui compte : si l'annuite ne couvre pas meme les interets de la premiere echeance,
     * le capital ne diminue jamais et l'echeancier n'aboutit pas. Un generateur naif produirait des
     * parts de capital negatives, un capital restant du croissant, et un echeancier d'apparence
     * normale sur les premieres lignes.
     */
    private static Money constantAnnuity(LoanTerms terms) {
        int count = terms.amortisingCount();
        BigDecimal rate = terms.periodicRate();
        Money annuity;
        if (rate.signum() == 0) {
            annuity = terms.principal().dividedBy(BigDecimal.valueOf(count)).roundToCurrency();
        } else {
            BigDecimal growth = BigDecimal.ONE.add(rate).pow(count, MathContexts.RATE);
            BigDecimal factor = rate.multiply(growth, MathContexts.RATE)
                .divide(growth.subtract(BigDecimal.ONE), MathContexts.RATE);
            annuity = terms.principal().times(factor).roundToCurrency();
        }

        Money firstInterest = terms.principal().times(rate).roundToCurrency();
        if (!annuity.isGreaterThan(firstInterest)) {
            throw new LoanTerms.InvalidLoanTermsException(
                "l'annuite calculee (" + annuity + ") ne couvre pas les interets de la premiere "
                + "echeance (" + firstInterest + ") : le capital ne serait jamais amorti. Allonger "
                + "la duree ou revoir le taux");
        }
        return annuity;
    }
}
