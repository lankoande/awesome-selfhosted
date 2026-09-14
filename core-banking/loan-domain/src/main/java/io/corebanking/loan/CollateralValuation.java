package io.corebanking.loan;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Valorisation des suretes d'un credit.
 *
 * <h2>Les quatre reductions, dans l'ordre</h2>
 *
 * <ol>
 *   <li><b>Le rang.</b> Une surete de second rang n'est couverte que par ce que les rangs
 *       anterieurs laissent de la valeur de l'actif. L'ignorer compte deux fois le meme immeuble.</li>
 *   <li><b>Le montant garanti.</b> Une hypotheque de 10 M sur un immeuble qui en vaut 30 ne couvre
 *       que 10 : la surete ne garantit que ce qu'elle inscrit.</li>
 *   <li><b>La quote-part.</b> Une meme surete peut garantir plusieurs credits ; la compter en
 *       entier sur chacun diviserait la provision du client par le nombre de ses credits.</li>
 *   <li><b>La quotite reglementaire</b>, qui vient du type de surete et jamais de la saisie.</li>
 * </ol>
 *
 * <h2>Deux exclusions, toujours signalees</h2>
 *
 * <p>Une <b>expertise perimee</b> ne vaut rien : une valeur d'il y a dix ans n'est pas une valeur.
 * Un <b>type sans quotite paramétree</b> n'est pas retenu non plus — le tenter a 100 % par defaut
 * serait exactement l'erreur que la quotite est faite d'empecher.
 *
 * <p>Dans les deux cas la surete est <b>ecartee et non ignoree</b> : le motif remonte. Une garantie
 * silencieusement exclue laisse croire a une couverture qui n'existe pas, et cela ne se decouvre
 * qu'a la realisation.
 */
public final class CollateralValuation {

    private CollateralValuation() {}

    /**
     * Surete accompagnee de ce que les rangs anterieurs mobilisent deja sur le meme actif.
     *
     * @param seniorSecured somme des montants garantis par les suretes de rang strictement
     *                      inferieur sur cet actif, toutes affectations confondues
     */
    public record Charged(CollateralCharge charge, Money seniorSecured) {}

    public static Coverage evaluate(List<Charged> charges, Map<String, CollateralPolicy> policies,
                                    LocalDate at, CurrencyRef currency) {
        Money total = Money.zero(currency);
        List<Coverage.Line> lines = new ArrayList<>(charges.size());

        for (Charged charged : charges) {
            CollateralCharge charge = charged.charge();
            CollateralPolicy policy = policies.get(charge.kind());
            if (policy == null) {
                lines.add(excluded(charge, currency,
                    "aucune quotite parametree pour le type de surete « " + charge.kind()
                    + " » : la surete est ecartee plutot que retenue a cent pour cent"));
                continue;
            }
            if (policy.valuationStale(charge.valuedOn(), at)) {
                lines.add(excluded(charge, currency,
                    "expertise du " + charge.valuedOn() + " perimee au " + at + " ("
                    + policy.maxValuationAgeMonths() + " mois au plus)"));
                continue;
            }

            // 1. Ce que les rangs anterieurs laissent de la valeur de l'actif.
            Money available = charge.assetValue().minus(charged.seniorSecured());
            if (available.isNegative()) {
                available = Money.zero(currency);
            }
            // 2. La surete ne couvre pas au-dela de ce qu'elle inscrit.
            Money retained = charge.securedAmount().isLessThan(available)
                ? charge.securedAmount() : available;
            if (!retained.isPositive()) {
                lines.add(excluded(charge, currency,
                    "rang " + charge.rank() + " integralement absorbe par les suretes anterieures"));
                continue;
            }
            // 3. La quote-part affectee a ce credit, puis 4. la quotite reglementaire.
            Money allocated = retained.times(charge.sharePercent().movePointLeft(2));
            Money eligible = allocated.times(policy.eligibleRatePercent().movePointLeft(2))
                                      .roundToCurrency();

            total = total.plus(eligible);
            lines.add(new Coverage.Line(charge.id(), charge.kind(), retained.roundToCurrency(),
                                        eligible, null));
        }
        return new Coverage(total, lines);
    }

    private static Coverage.Line excluded(CollateralCharge charge, CurrencyRef currency,
                                          String reason) {
        Money zero = Money.zero(currency);
        return new Coverage.Line(charge.id(), charge.kind(), zero, zero, reason);
    }
}
