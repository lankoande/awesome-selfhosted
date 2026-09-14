package io.corebanking.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les refus de parametrage. Chacun de ces cas, accepte, produirait des montants faux sur des
 * comptes clients sans lever aucune exception a l'execution.
 */
class FeeTermsTest {

    private static final UUID PRODUIT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final LocalDate ANCRAGE = LocalDate.of(2026, 9, 1);

    private static FeeTerms.Builder base() {
        return new FeeTerms.Builder("COM", "Commission", Currencies.XOF)
            .anchor(ANCRAGE).incomeAccount(PRODUIT);
    }

    @Test
    @DisplayName("un plafond non imputable est refuse : l'arrondi le franchirait")
    void plafondNonImputable() {
        // 4 999,60 XOF arrondi a l'echelle de la devise donne 5 000 : le plafond serait depasse
        // par l'operation meme qui est censee le faire respecter.
        assertThatThrownBy(() -> base().basis(FeeBasis.FLAT).flatAmount(Money.of("6000", Currencies.XOF))
            .cap(Money.of("4999.6", Currencies.XOF)).build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("n'est pas imputable");
    }

    @Test
    @DisplayName("un plancher superieur au plafond est refuse")
    void plancherAuDessusDuPlafond() {
        assertThatThrownBy(() -> base().basis(FeeBasis.FLAT).flatAmount(Money.of("1000", Currencies.XOF))
            .floor(Money.of("3000", Currencies.XOF)).cap(Money.of("2000", Currencies.XOF)).build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("superieure au plafond");
    }

    @Test
    @DisplayName("une taxe sans compte de taxe est refusee : la taxe collectee est une dette")
    void taxeSansCompte() {
        assertThatThrownBy(() -> base().basis(FeeBasis.FLAT).flatAmount(Money.of("1000", Currencies.XOF))
            .taxRatePercent("18").build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("compte de taxe");
    }

    @Test
    @DisplayName("une assiette au taux sans taux est refusee")
    void tauxManquant() {
        assertThatThrownBy(() -> base().basis(FeeBasis.RATE_ON_CLOSING_BALANCE).build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("exige un taux");
    }

    @Test
    @DisplayName("un montant libelle dans une autre devise est refuse")
    void deviseIncoherente() {
        assertThatThrownBy(() -> base().basis(FeeBasis.FLAT)
            .flatAmount(Money.of("10", Currencies.EUR)).build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("le report sans anciennete maximale est refuse : une creance eternelle fausse le produit a recevoir")
    void reportSansHorizon() {
        assertThatThrownBy(() -> base().basis(FeeBasis.FLAT).flatAmount(Money.of("1000", Currencies.XOF))
            .onInsufficientFunds(InsufficientFundsPolicy.DEFER).build())
            .isInstanceOf(FeeTerms.InvalidFeeTermsException.class)
            .hasMessageContaining("anciennete maximale");
    }

    @Test
    @DisplayName("a terme a echoir, la perception tombe au premier jour de la periode couverte")
    void dateDePerception() {
        FeeTerms echu = base().basis(FeeBasis.FLAT).flatAmount(Money.of("1000", Currencies.XOF))
            .timing(FeeTiming.IN_ARREARS).build();
        FeeTerms aEchoir = base().basis(FeeBasis.FLAT).flatAmount(Money.of("1000", Currencies.XOF))
            .timing(FeeTiming.IN_ADVANCE).build();

        assertThat(echu.chargeDate(echu.period(0))).isEqualTo("2026-09-30");
        assertThat(aEchoir.chargeDate(aEchoir.period(0))).isEqualTo("2026-09-01");
    }
}
