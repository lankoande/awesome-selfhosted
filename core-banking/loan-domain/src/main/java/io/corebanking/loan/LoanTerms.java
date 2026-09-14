package io.corebanking.loan;

import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Conditions contractuelles d'un credit, telles qu'elles engendrent l'echeancier.
 *
 * <p>Toutes les incoherences sont refusees ici. Un echeancier faux ne se voit pas : il est
 * equilibre, plausible, et se decouvre a la derniere echeance, quand le capital restant du n'est
 * pas nul.
 *
 * @param graceInstalments nombre d'echeances en differe d'amortissement : seuls les interets sont
 *                         servis, le capital reste intact. A distinguer du differe total, ou les
 *                         interets eux-memes sont capitalises — celui-la n'est pas couvert ici, et
 *                         son absence est signalee plutot que contournee.
 * @param periodicFee      frais fixes par echeance, nuls s'il n'y en a pas
 * @param taxOnInterestRatePercent taxe assise sur les interets de l'echeance (TAF, TOB)
 */
public record LoanTerms(
    Money principal,
    CurrencyRef currency,
    BigDecimal annualRatePercent,
    Periodicity frequency,
    int instalmentCount,
    int graceInstalments,
    LocalDate disbursedOn,
    LocalDate firstDueDate,
    AmortisationMethod method,
    DayCountConvention dayCount,
    Money periodicFee,
    InsuranceBasis insuranceBasis,
    BigDecimal insuranceRatePercent,
    BigDecimal taxOnInterestRatePercent) {

    public LoanTerms {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(disbursedOn, "disbursedOn");
        Objects.requireNonNull(firstDueDate, "firstDueDate");
        Objects.requireNonNull(insuranceBasis, "insuranceBasis");
        annualRatePercent = requireRate(annualRatePercent, "taux nominal");
        insuranceRatePercent = requireRate(insuranceRatePercent, "taux d'assurance");
        taxOnInterestRatePercent = requireRate(taxOnInterestRatePercent, "taux de taxe");

        Objects.requireNonNull(principal, "principal");
        require(principal.isPositive(), "capital nul ou negatif : " + principal);
        require(principal.currency().equals(currency),
                "capital libelle en " + principal.currency() + " et credit en " + currency);
        requireBookable(principal, "le capital");

        periodicFee = periodicFee == null ? Money.zero(currency) : periodicFee;
        require(!periodicFee.isNegative(), "frais par echeance negatifs");
        require(periodicFee.currency().equals(currency), "frais libelles dans une autre devise");
        requireBookable(periodicFee, "les frais par echeance");

        require(instalmentCount > 0, "nombre d'echeances nul ou negatif : " + instalmentCount);
        require(graceInstalments >= 0, "nombre d'echeances en differe negatif");
        require(graceInstalments < instalmentCount,
                "differe de " + graceInstalments + " echeances sur " + instalmentCount
                + " : il ne resterait aucune echeance pour amortir le capital");
        require(firstDueDate.isAfter(disbursedOn),
                "premiere echeance au " + firstDueDate + ", anterieure ou egale au deblocage du "
                + disbursedOn);
        require(insuranceBasis == InsuranceBasis.NONE || insuranceRatePercent.signum() > 0,
                "une assiette d'assurance est designee sans taux");
        require(insuranceBasis != InsuranceBasis.NONE || insuranceRatePercent.signum() == 0,
                "un taux d'assurance est parametre sans assiette : la prime ne serait jamais "
                + "calculee, et le cout total du credit serait sous-estime");
    }

    /** Nombre d'echeances qui amortissent reellement le capital. */
    public int amortisingCount() {
        return instalmentCount - graceInstalments;
    }

    /** Taux periodique proportionnel, en fraction : 12 % l'an sur douze mois donnent 0,01. */
    public BigDecimal periodicRate() {
        return annualRatePercent.movePointLeft(2)
            .divide(BigDecimal.valueOf(frequency.periodsPerYear()),
                    io.corebanking.interest.rate.MathContexts.RATE);
    }

    /**
     * Memes conditions, appliquees au capital restant du sur une duree reduite.
     *
     * <p>Sert au remboursement anticipe et au rechelonnement : le taux, la convention de jours,
     * l'assurance et les frais restent ceux du contrat — seuls le capital, la duree et le point de
     * depart changent. Reconduire un taux revise au passage transformerait un remboursement
     * anticipe en renegociation, ce qui est une autre operation et un autre consentement.
     *
     * <p>Le differe ne se reconduit pas : il a ete consomme.
     */
    public LoanTerms forRemaining(Money remaining, int instalments, LocalDate from,
                                  LocalDate nextDueDate) {
        return new LoanTerms(remaining, currency, annualRatePercent, frequency, instalments, 0,
                             from, nextDueDate, method, dayCount, periodicFee, insuranceBasis,
                             insuranceRatePercent, taxOnInterestRatePercent);
    }

    /** Date d'echeance de rang donne, de 1 a {@link #instalmentCount()}. */
    public LocalDate dueDate(int number) {
        require(number >= 1 && number <= instalmentCount,
                "echeance " + number + " hors de l'echeancier [1.." + instalmentCount + "]");
        return frequency.startOfPeriod(firstDueDate, number - 1);
    }

    private static BigDecimal requireRate(BigDecimal rate, String label) {
        BigDecimal value = rate == null ? BigDecimal.ZERO : rate;
        require(value.signum() >= 0, label + " negatif : " + value);
        return value;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new InvalidLoanTermsException(detail);
        }
    }

    private static void requireBookable(Money amount, String what) {
        require(amount.isBookable(),
                what + " " + amount + " n'est pas imputable en " + amount.currency());
    }

    public static Builder of(Money principal) {
        return new Builder(principal);
    }

    /**
     * Assemblage des conditions. Quatorze composantes dont la moitie sont optionnelles : un
     * constructeur positionnel rendrait un taux d'assurance et un taux de taxe interchangeables a
     * la relecture, et l'inversion ne se verrait qu'au premier echeancier edite.
     */
    public static final class Builder {
        private final Money principal;
        private BigDecimal annualRatePercent = BigDecimal.ZERO;
        private Periodicity frequency = Periodicity.MONTHLY;
        private int instalmentCount = 12;
        private int graceInstalments;
        private LocalDate disbursedOn;
        private LocalDate firstDueDate;
        private AmortisationMethod method = AmortisationMethod.CONSTANT_ANNUITY;
        private DayCountConvention dayCount = DayCountConvention.ACT_365;
        private Money periodicFee;
        private InsuranceBasis insuranceBasis = InsuranceBasis.NONE;
        private BigDecimal insuranceRatePercent = BigDecimal.ZERO;
        private BigDecimal taxOnInterestRatePercent = BigDecimal.ZERO;

        private Builder(Money principal) {
            this.principal = principal;
        }

        public Builder ratePercent(BigDecimal value)   { this.annualRatePercent = value; return this; }
        public Builder ratePercent(String value)       { return ratePercent(new BigDecimal(value)); }
        public Builder frequency(Periodicity value)    { this.frequency = value; return this; }
        public Builder instalments(int value)          { this.instalmentCount = value; return this; }
        public Builder grace(int value)                { this.graceInstalments = value; return this; }
        public Builder disbursedOn(LocalDate value)    { this.disbursedOn = value; return this; }
        public Builder firstDueDate(LocalDate value)   { this.firstDueDate = value; return this; }
        public Builder method(AmortisationMethod v)    { this.method = v; return this; }
        public Builder dayCount(DayCountConvention v)  { this.dayCount = v; return this; }
        public Builder periodicFee(Money value)        { this.periodicFee = value; return this; }
        public Builder insuranceRatePercent(String v)  { this.insuranceRatePercent = new BigDecimal(v); return this; }
        public Builder taxOnInterestPercent(String v)  { this.taxOnInterestRatePercent = new BigDecimal(v); return this; }

        public Builder insurance(InsuranceBasis basis, String ratePercent) {
            this.insuranceBasis = basis;
            this.insuranceRatePercent = new BigDecimal(ratePercent);
            return this;
        }

        public LoanTerms build() {
            return new LoanTerms(principal, principal.currency(), annualRatePercent, frequency,
                                 instalmentCount, graceInstalments, disbursedOn, firstDueDate,
                                 method, dayCount, periodicFee, insuranceBasis, insuranceRatePercent,
                                 taxOnInterestRatePercent);
        }
    }

    /** Conditions de credit refusees. */
    public static class InvalidLoanTermsException extends RuntimeException {
        public InvalidLoanTermsException(String detail) {
            super("Conditions du credit : " + detail + ".");
        }
    }
}
