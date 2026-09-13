package io.corebanking.interest;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.interest.accrual.InterestBasis;
import io.corebanking.interest.accrual.InterestCalculator;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** Proprietes d'exactitude du moteur d'interets. */
class InterestExactnessProperties {

    private static final LocalDate DEBUT = LocalDate.of(2026, 1, 1);

    /**
     * Additivite : decouper une periode ne change pas le montant total.
     *
     * <p>C'est la propriete qui garantit qu'un TFJ quotidien produit exactement le meme resultat
     * qu'un calcul mensuel d'un seul tenant. Sans elle, la reprise d'un arrete ou un changement de
     * frequence de traitement modifierait les montants factures.
     */
    @Property(tries = 300)
    void le_decoupage_dune_periode_ne_change_pas_le_total(
            @ForAll("soldes") List<Long> soldes,
            @ForAll @IntRange(min = 1, max = 29) int coupure) {
        List<DailyBalance> serie = serie(soldes);
        if (coupure >= serie.size()) {
            return;
        }
        var rates = FlatRate.of("4.25");
        var dc = DayCountConvention.ACT_365;

        Money total = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, rates, dc).total();
        Money premiere = InterestCalculator.accrue(serie.subList(0, coupure),
            InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR, rates, dc).total();
        Money seconde = InterestCalculator.accrue(serie.subList(coupure, serie.size()),
            InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR, rates, dc).total();

        assertThat(premiere.plus(seconde)).isEqualTo(total);
    }

    /** Un solde superieur ne peut jamais produire moins d'interets. */
    @Property(tries = 300)
    void les_interets_sont_monotones_en_fonction_du_solde(
            @ForAll("soldes") List<Long> soldes,
            @ForAll @IntRange(min = 1, max = 1_000_000) int supplement) {
        var rates = new TieredRate(List.of(
            Tier.of("0", "5000000", "2"), Tier.of("5000000", null, "3")), TieringMode.PROGRESSIVE);
        var dc = DayCountConvention.ACT_360;

        List<Long> majores = soldes.stream().map(s -> s + supplement).toList();

        Money base = InterestCalculator.accrue(serie(soldes), InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, rates, dc).total();
        Money majore = InterestCalculator.accrue(serie(majores), InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, rates, dc).total();

        assertThat(majore.isGreaterThanOrEqual(base)).isTrue();
    }

    /**
     * Les deux cotes de l'echelle sont disjoints : aucune journee n'alimente a la fois les nombres
     * crediteurs et les nombres debiteurs.
     */
    @Property(tries = 300)
    void les_deux_cotes_de_lechelle_sont_disjoints(@ForAll("soldesSignes") List<Long> soldes) {
        List<DailyBalance> serie = serie(soldes);
        var crediteur = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("3"), DayCountConvention.ACT_365);
        var debiteur = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.DEBTOR, FlatRate.of("3"), DayCountConvention.ACT_365);

        for (int i = 0; i < serie.size(); i++) {
            boolean cote1 = crediteur.days().get(i).basisBalance().isPositive();
            boolean cote2 = debiteur.days().get(i).basisBalance().isPositive();
            assertThat(cote1 && cote2).isFalse();
        }
    }

    /**
     * Le bareme global ne peut jamais rendre moins que le progressif quand les taux croissent avec
     * les tranches : la tranche superieure s'applique alors a l'integralite du solde.
     */
    @Property(tries = 200)
    void le_bareme_global_domine_le_progressif_a_taux_croissants(
            @ForAll("soldes") List<Long> soldes) {
        List<Tier> tranches = List.of(
            Tier.of("0", "1000000", "1"),
            Tier.of("1000000", "5000000", "2.5"),
            Tier.of("5000000", null, "4"));
        List<DailyBalance> serie = serie(soldes);

        Money progressif = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, new TieredRate(tranches, TieringMode.PROGRESSIVE),
            DayCountConvention.ACT_365).total();
        Money global = InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, new TieredRate(tranches, TieringMode.WHOLE_BALANCE),
            DayCountConvention.ACT_365).total();

        assertThat(global.isGreaterThanOrEqual(progressif)).isTrue();
    }

    /** L'arrondi final ne s'ecarte jamais de plus d'une demi-unite du montant exact. */
    @Property(tries = 300)
    void larrondi_final_reste_borne_a_une_demi_unite(@ForAll("soldes") List<Long> soldes) {
        var accrual = InterestCalculator.accrue(serie(soldes), InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("6.75"), DayCountConvention.ACT_365);

        Money ecart = accrual.roundingRemainder().abs();
        assertThat(ecart.isLessThan(Money.of("0.5", XOF)) || ecart.equals(Money.of("0.5", XOF)))
            .isTrue();
        assertThat(accrual.bookableAmount().isBookable()).isTrue();
    }

    // ------------------------------------------------------------------ generateurs

    private static List<DailyBalance> serie(List<Long> soldes) {
        List<DailyBalance> serie = new ArrayList<>(soldes.size());
        for (int i = 0; i < soldes.size(); i++) {
            serie.add(new DailyBalance(DEBUT.plusDays(i),
                                       Money.of(BigDecimal.valueOf(soldes.get(i)), XOF)));
        }
        return serie;
    }

    @Provide
    Arbitrary<List<Long>> soldes() {
        return Arbitraries.longs().between(0, 50_000_000_000L).list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<List<Long>> soldesSignes() {
        return Arbitraries.longs().between(-20_000_000_000L, 20_000_000_000L)
            .list().ofMinSize(1).ofMaxSize(40);
    }
}
