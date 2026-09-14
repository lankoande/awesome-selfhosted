package io.corebanking.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeFrequencyTest {

    @Test
    @DisplayName("une echeance au 31 ramenee au 28 en fevrier revient au 31 en mars")
    void pasDeDeriveDeFinDeMois() {
        LocalDate ancrage = LocalDate.of(2026, 1, 31);

        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 0)).isEqualTo("2026-01-31");
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 1)).isEqualTo("2026-02-28");

        // Le point du test. En deduisant chaque echeance de la precedente, mars tomberait le 28 et
        // le contrat aurait change de jour d'echeance sans qu'aucune decision ne l'ait voulu.
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 2)).isEqualTo("2026-03-31");
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 3)).isEqualTo("2026-04-30");
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 4)).isEqualTo("2026-05-31");
    }

    @Test
    @DisplayName("2028 est bissextile : l'echeance de fevrier tombe le 29, et mars revient au 31")
    void anneeBissextile() {
        LocalDate ancrage = LocalDate.of(2028, 1, 31);
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 1)).isEqualTo("2028-02-29");
        assertThat(FeeFrequency.MONTHLY.startOfPeriod(ancrage, 2)).isEqualTo("2028-03-31");
    }

    @Test
    @DisplayName("les periodes successives sont jointives : aucun jour n'echappe ni ne compte deux fois")
    void periodesJointives() {
        for (FeeFrequency frequence : FeeFrequency.values()) {
            LocalDate ancrage = LocalDate.of(2026, 1, 31);
            for (int rang = 0; rang < 40; rang++) {
                FeePeriod courante = frequence.period(ancrage, rang);
                FeePeriod suivante = frequence.period(ancrage, rang + 1);
                assertThat(courante.end().plusDays(1))
                    .as("%s, rang %d", frequence, rang)
                    .isEqualTo(suivante.start());
            }
        }
    }

    @Test
    @DisplayName("tout jour depuis l'ancrage appartient a exactement une periode, retrouvee par son rang")
    void rangRetrouveLaPeriode() {
        LocalDate ancrage = LocalDate.of(2026, 1, 31);
        for (FeeFrequency frequence : FeeFrequency.values()) {
            for (LocalDate jour = ancrage; jour.isBefore(ancrage.plusYears(3)); jour = jour.plusDays(1)) {
                int rang = frequence.indexOfPeriodContaining(ancrage, jour);
                assertThat(frequence.period(ancrage, rang).contains(jour))
                    .as("%s, %s", frequence, jour)
                    .isTrue();
            }
        }
    }

    @Test
    @DisplayName("une date anterieure a l'ancrage n'a pas de periode : c'est une erreur, pas un rang negatif")
    void avantAncrage() {
        LocalDate ancrage = LocalDate.of(2026, 1, 31);
        assertThatThrownBy(() ->
            FeeFrequency.MONTHLY.indexOfPeriodContaining(ancrage, ancrage.minusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("precede l'ancrage");
    }

    @Test
    @DisplayName("la duree d'une periode suit le calendrier, elle n'est pas conventionnelle")
    void dureeReelle() {
        LocalDate ancrage = LocalDate.of(2026, 1, 1);
        assertThat(FeeFrequency.MONTHLY.period(ancrage, 0).days()).isEqualTo(31);
        assertThat(FeeFrequency.MONTHLY.period(ancrage, 1).days()).isEqualTo(28);
        assertThat(FeeFrequency.QUARTERLY.period(ancrage, 0).days()).isEqualTo(31 + 28 + 31);
        assertThat(FeeFrequency.ANNUAL.period(ancrage, 0).days()).isEqualTo(365);
        assertThat(FeeFrequency.DAILY.period(ancrage, 7).days()).isEqualTo(1);
    }

    @Test
    @DisplayName("les jours servis se comptent sur l'intersection avec la vie du compte")
    void joursServis() {
        FeePeriod septembre = FeeFrequency.MONTHLY.period(LocalDate.of(2026, 9, 1), 0);

        assertThat(septembre.daysWithin(null, null)).isEqualTo(30);
        assertThat(septembre.daysWithin(LocalDate.of(2026, 9, 16), null)).isEqualTo(15);
        assertThat(septembre.daysWithin(null, LocalDate.of(2026, 9, 10))).isEqualTo(10);
        assertThat(septembre.daysWithin(LocalDate.of(2026, 10, 1), null)).isZero();
        assertThat(septembre.daysWithin(null, LocalDate.of(2026, 8, 31))).isZero();
    }
}
