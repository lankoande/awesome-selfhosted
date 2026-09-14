package io.corebanking.fee;

import io.corebanking.interest.rate.RateSchedule;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.kernel.time.SchedulePeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Conditions de perception d'une commission, telles qu'en vigueur a une date.
 *
 * <p>Toutes les incoherences de parametrage sont refusees ici, a la construction. Le moment ou une
 * commission mal parametree est decouverte decide de son cout : au deploiement du parametrage,
 * elle se corrige ; au milieu d'un traitement de masse, elle a deja produit des ecritures sur une
 * partie du portefeuille.
 *
 * @param anchor origine des periodes. Deux ancrages ont un sens : une date fixe du produit — tous
 *               les comptes sont factures aux memes echeances — ou la date d'ouverture du compte,
 *               chacun suivant alors son propre cycle. Le second lisse la charge du traitement ; le
 *               premier facilite le rapprochement avec les etats de gestion.
 * @param floor  perception minimale, nulle si absente
 * @param cap    perception maximale, nulle si absente
 */
public record FeeTerms(
    String code,
    String label,
    CurrencyRef currency,
    Periodicity frequency,
    LocalDate anchor,
    FeeTiming timing,
    FeeBasis basis,
    Money flatAmount,
    BigDecimal ratePercent,
    RateSchedule tiers,
    Money floor,
    Money cap,
    Proration proration,
    BigDecimal taxRatePercent,
    UUID incomeAccount,
    UUID taxAccount,
    InsufficientFundsPolicy onInsufficientFunds,
    int arrearMaxAgeDays) {

    public FeeTerms {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(proration, "proration");
        Objects.requireNonNull(onInsufficientFunds, "onInsufficientFunds");
        Objects.requireNonNull(taxRatePercent, "taxRatePercent");
        Objects.requireNonNull(incomeAccount,
            "incomeAccount : une commission percue est un produit, elle a un compte de produit.");

        switch (basis) {
            case FLAT -> {
                require(flatAmount != null, code, "l'assiette FLAT exige un montant forfaitaire");
                require(!flatAmount.isNegative(), code, "montant forfaitaire negatif");
                requireBookable(flatAmount, code, "le montant forfaitaire");
            }
            case RATE_ON_CLOSING_BALANCE, RATE_ON_HIGHEST_DEBIT_BALANCE -> {
                require(ratePercent != null, code, "l'assiette " + basis + " exige un taux");
                require(ratePercent.signum() >= 0, code, "taux negatif " + ratePercent);
            }
            case TIERED_ON_CLOSING_BALANCE ->
                require(tiers != null, code, "l'assiette par tranches exige un bareme");
        }

        requireSameCurrency(flatAmount, currency, code, "le montant forfaitaire");
        requireSameCurrency(floor, currency, code, "la perception minimale");
        requireSameCurrency(cap, currency, code, "la perception maximale");

        // Une borne non comptabilisable serait franchie par l'arrondi : un plafond de 4 999,60 XOF
        // laisserait passer une perception de 5 000. Les bornes sont donc exigees imputables.
        if (floor != null) {
            require(!floor.isNegative(), code, "perception minimale negative");
            requireBookable(floor, code, "la perception minimale");
        }
        if (cap != null) {
            require(!cap.isNegative(), code, "perception maximale negative");
            requireBookable(cap, code, "la perception maximale");
        }
        if (floor != null && cap != null) {
            require(!floor.isGreaterThan(cap), code,
                    "perception minimale " + floor + " superieure au plafond " + cap);
        }

        require(taxRatePercent.signum() >= 0, code, "taux de taxe negatif " + taxRatePercent);
        require(taxRatePercent.signum() == 0 || taxAccount != null, code,
                "un taux de taxe de " + taxRatePercent + " % est parametre sans compte de taxe : "
                + "la taxe collectee est une dette envers l'administration, elle a son compte.");
        require(arrearMaxAgeDays >= 0, code, "anciennete maximale d'impaye negative");
        require(onInsufficientFunds != InsufficientFundsPolicy.DEFER || arrearMaxAgeDays > 0, code,
                "le report exige une anciennete maximale : une creance representee indefiniment "
                + "n'est jamais abandonnee et fausse le produit a recevoir.");
    }

    /**
     * Memes conditions, ancrees sur une autre date.
     *
     * <p>Sert lorsque l'ancrage est celui du compte et non du produit : les conditions sont lues
     * une fois pour le produit, puis reancrees compte par compte, sans relire le parametrage.
     */
    public FeeTerms withAnchor(LocalDate newAnchor) {
        return newAnchor.equals(anchor) ? this
            : new FeeTerms(code, label, currency, frequency, newAnchor, timing, basis, flatAmount,
                           ratePercent, tiers, floor, cap, proration, taxRatePercent, incomeAccount,
                           taxAccount, onInsufficientFunds, arrearMaxAgeDays);
    }

    /** Date de perception de la periode de rang donne. */
    public LocalDate chargeDate(SchedulePeriod period) {
        return timing.chargeDate(period);
    }

    public SchedulePeriod period(int index) {
        return frequency.period(anchor, index);
    }

    private static void require(boolean condition, String code, String detail) {
        if (!condition) {
            throw new InvalidFeeTermsException(code, detail);
        }
    }

    private static void requireBookable(Money amount, String code, String what) {
        require(amount.isBookable(), code,
                what + " " + amount + " n'est pas imputable en " + amount.currency()
                + " : l'arrondi le deplacerait a l'insu du parametrage");
    }

    private static void requireSameCurrency(Money amount, CurrencyRef currency, String code,
                                            String what) {
        require(amount == null || amount.currency().equals(currency), code,
                what + " est libelle en " + (amount == null ? "?" : amount.currency())
                + " alors que la commission est en " + currency);
    }

    /**
     * Assemblage des conditions. Dix-huit composantes dont la moitie sont facultatives selon
     * l'assiette : un constructeur positionnel les rendrait interchangeables a la relecture, et
     * une inversion de deux montants ne se verrait pas.
     */
    public static final class Builder {
        private final String code;
        private final String label;
        private final CurrencyRef currency;
        private Periodicity frequency = Periodicity.MONTHLY;
        private LocalDate anchor;
        private FeeTiming timing = FeeTiming.IN_ARREARS;
        private FeeBasis basis = FeeBasis.FLAT;
        private Money flatAmount;
        private BigDecimal ratePercent;
        private RateSchedule tiers;
        private Money floor;
        private Money cap;
        private Proration proration = Proration.NONE;
        private BigDecimal taxRatePercent = BigDecimal.ZERO;
        private UUID incomeAccount;
        private UUID taxAccount;
        private InsufficientFundsPolicy onInsufficientFunds = InsufficientFundsPolicy.REJECT;
        private int arrearMaxAgeDays;

        public Builder(String code, String label, CurrencyRef currency) {
            this.code = code;
            this.label = label;
            this.currency = currency;
        }

        public Builder frequency(Periodicity value)  { this.frequency = value; return this; }
        public Builder anchor(LocalDate value)        { this.anchor = value; return this; }
        public Builder timing(FeeTiming value)        { this.timing = value; return this; }
        public Builder basis(FeeBasis value)          { this.basis = value; return this; }
        public Builder flatAmount(Money value)        { this.flatAmount = value; return this; }
        public Builder ratePercent(BigDecimal value)  { this.ratePercent = value; return this; }
        public Builder ratePercent(String value)      { return ratePercent(new BigDecimal(value)); }
        public Builder tiers(RateSchedule value)      { this.tiers = value; return this; }
        public Builder floor(Money value)             { this.floor = value; return this; }
        public Builder cap(Money value)               { this.cap = value; return this; }
        public Builder proration(Proration value)     { this.proration = value; return this; }
        public Builder taxRatePercent(BigDecimal v)   { this.taxRatePercent = v; return this; }
        public Builder taxRatePercent(String value)   { return taxRatePercent(new BigDecimal(value)); }
        public Builder incomeAccount(UUID value)      { this.incomeAccount = value; return this; }
        public Builder taxAccount(UUID value)         { this.taxAccount = value; return this; }
        public Builder arrearMaxAgeDays(int value)    { this.arrearMaxAgeDays = value; return this; }

        public Builder onInsufficientFunds(InsufficientFundsPolicy value) {
            this.onInsufficientFunds = value;
            return this;
        }

        public FeeTerms build() {
            return new FeeTerms(code, label, currency, frequency, anchor, timing, basis, flatAmount,
                                ratePercent, tiers, floor, cap, proration, taxRatePercent,
                                incomeAccount, taxAccount, onInsufficientFunds, arrearMaxAgeDays);
        }
    }

    /** Parametrage de commission refuse. */
    public static class InvalidFeeTermsException extends RuntimeException {
        public InvalidFeeTermsException(String code, String detail) {
            super("Commission " + code + " : " + detail + ".");
        }
    }
}
