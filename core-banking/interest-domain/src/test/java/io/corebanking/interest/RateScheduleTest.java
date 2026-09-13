package io.corebanking.interest;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieredRate;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateScheduleTest {

    private static final BigDecimal UNE_ANNEE = BigDecimal.ONE;

    private static final List<Tier> BAREME = List.of(
        Tier.of("0", "5000000", "2"),
        Tier.of("5000000", null, "3"));

    @Test
    @DisplayName("taux unique : 10 000 000 a 8 % sur un an font 800 000")
    void flat_rate_is_exact() {
        assertThat(FlatRate.of("8").accrue(Money.of("10000000", XOF), UNE_ANNEE))
            .isEqualTo(Money.of("800000", XOF));
    }

    @Test
    @DisplayName("progressif et global divergent de 50 000 sur le meme bareme et le meme solde")
    void progressive_and_whole_balance_are_not_interchangeable() {
        Money solde = Money.of("6000000", XOF);

        Money progressif = new TieredRate(BAREME, TieringMode.PROGRESSIVE).accrue(solde, UNE_ANNEE);
        Money global = new TieredRate(BAREME, TieringMode.WHOLE_BALANCE).accrue(solde, UNE_ANNEE);

        assertThat(progressif).isEqualTo(Money.of("130000", XOF));   // 5 M a 2 % + 1 M a 3 %
        assertThat(global).isEqualTo(Money.of("180000", XOF));       // 6 M a 3 %
        assertThat(global.minus(progressif)).isEqualTo(Money.of("50000", XOF));
    }

    @Test
    @DisplayName("un bareme lacunaire est rejete a la construction, pas decouvert au TFJ")
    void gapped_tiers_are_rejected_at_construction() {
        assertThatThrownBy(() -> new TieredRate(List.of(
            Tier.of("0", "1000000", "2"),
            Tier.of("2000000", null, "3")), TieringMode.PROGRESSIVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non contigues");
    }

    @Test
    @DisplayName("un bareme qui ne part pas de zero est rejete")
    void tiers_must_start_at_zero() {
        assertThatThrownBy(() -> new TieredRate(List.of(Tier.of("1000", null, "2")),
                                                TieringMode.PROGRESSIVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partir de zero");
    }

    @Test
    @DisplayName("un bareme non ouvert vers le haut laisserait un solde sans taux")
    void last_tier_must_be_open() {
        assertThatThrownBy(() -> new TieredRate(List.of(Tier.of("0", "1000000", "2")),
                                                TieringMode.PROGRESSIVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ouverte vers le haut");
    }

    @Test
    @DisplayName("un solde nul ou negatif ne produit aucun interet crediteur")
    void non_positive_balance_yields_nothing() {
        var bareme = new TieredRate(BAREME, TieringMode.PROGRESSIVE);
        assertThat(bareme.accrue(Money.zero(XOF), UNE_ANNEE)).isEqualTo(Money.zero(XOF));
        assertThat(bareme.accrue(Money.of("-500000", XOF), UNE_ANNEE)).isEqualTo(Money.zero(XOF));
    }
}
