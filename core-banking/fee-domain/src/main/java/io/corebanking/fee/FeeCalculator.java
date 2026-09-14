package io.corebanking.fee;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Liquidation d'une commission : de l'assiette au montant impute.
 *
 * <p>Fonction pure, sans acces aux donnees : l'assiette lui est fournie constatee. C'est ce qui
 * permet de la verifier sur des cas ecrits a la main, la ou une liquidation melee a ses lectures
 * ne se teste qu'a travers un jeu de donnees.
 *
 * <h2>L'ordre des operations, qui decide du montant</h2>
 *
 * <ol>
 *   <li><b>assiette</b> — forfait, taux sur solde, taux sur plus fort decouvert, bareme ;</li>
 *   <li><b>prorata</b> — au nombre de jours servis ;</li>
 *   <li><b>bornes</b> — plancher puis plafond, apres le prorata et non avant : le plancher couvre
 *       un cout de traitement fixe, qui ne diminue pas parce que la periode est incomplete. Une
 *       banque qui veut l'inverse prorate son plancher dans son parametrage ;</li>
 *   <li><b>arrondi du net</b> a l'echelle de la devise ;</li>
 *   <li><b>taxe sur le net arrondi</b>, puis arrondie a son tour.</li>
 * </ol>
 *
 * <p>Le point 5 est le plus important. Calculer la taxe sur le montant non arrondi puis arrondir
 * l'ensemble donne un total juste au centime pres et une <b>base declaree fausse</b> : l'assiette
 * de la taxe collectee ne serait pas le montant reellement porte au compte de produit. La taxe est
 * donc assise sur ce qui est effectivement percu.
 */
public final class FeeCalculator {

    private FeeCalculator() {}

    public static FeeAssessment assess(FeeTerms terms, FeePeriod period, Money basisAmount,
                                       int chargedDays) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(period, "period");
        if (chargedDays < 0 || chargedDays > period.days()) {
            throw new IllegalArgumentException(
                "Jours factures hors de la periode : " + chargedDays + " sur " + period.days());
        }
        Money zero = Money.zero(terms.currency());

        // Une periode sans jour servi ne produit rien — pas meme le plancher. Appliquer une
        // perception minimale a un compte clos avant l'ouverture de la periode facturerait un
        // service qui n'a pas existe.
        if (chargedDays == 0) {
            return new FeeAssessment(period, basisAmount == null ? zero : basisAmount,
                                     zero, zero, zero, zero, 0, period.days());
        }

        Money basis = requireUsableBasis(terms, basisAmount, zero);
        Money raw = rawAmount(terms, basis);

        Money prorated = terms.proration() == Proration.ACTUAL_DAYS && chargedDays < period.days()
            ? raw.times(BigDecimal.valueOf(chargedDays)).dividedBy(BigDecimal.valueOf(period.days()))
            : raw;

        Money bounded = prorated;
        if (terms.floor() != null && bounded.isLessThan(terms.floor())) {
            bounded = terms.floor();
        }
        if (terms.cap() != null && bounded.isGreaterThan(terms.cap())) {
            bounded = terms.cap();
        }

        Money net = bounded.roundToCurrency();
        Money tax = net.times(terms.taxRatePercent().movePointLeft(2)).roundToCurrency();
        return new FeeAssessment(period, basis, bounded, net, tax, net.plus(tax), chargedDays,
                                 period.days());
    }

    private static Money rawAmount(FeeTerms terms, Money basis) {
        return switch (terms.basis()) {
            case FLAT -> terms.flatAmount();
            case RATE_ON_CLOSING_BALANCE, RATE_ON_HIGHEST_DEBIT_BALANCE ->
                // movePointLeft plutot qu'une division : le passage du pourcentage au coefficient
                // est exact, et n'ajoute pas un arrondi intermediaire au seul arrondi legitime.
                basis.times(terms.ratePercent().movePointLeft(2));
            case TIERED_ON_CLOSING_BALANCE ->
                // Un bareme de commission et un bareme de taux sont le meme calcul ; la fraction
                // d'annee unitaire fait du taux annuel un pourcentage applique tel quel.
                terms.tiers().accrue(basis, BigDecimal.ONE);
        };
    }

    private static Money requireUsableBasis(FeeTerms terms, Money basisAmount, Money zero) {
        if (!terms.basis().needsBalance()) {
            return basisAmount == null ? zero : basisAmount;
        }
        Objects.requireNonNull(basisAmount,
            "Assiette absente pour la commission " + terms.code() + " : l'assiette " + terms.basis()
            + " se constate sur le compte. La remplacer par zero produirait une perception nulle,"
            + " equilibree et fausse.");
        if (basisAmount.isNegative()) {
            throw new IllegalArgumentException(
                "Assiette negative " + basisAmount + " pour la commission " + terms.code()
                + ". Le sens de l'assiette releve de son constat, pas de sa liquidation : un solde"
                + " crediteur et un decouvert ne se compensent pas.");
        }
        return basisAmount;
    }
}
