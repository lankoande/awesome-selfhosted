package io.corebanking.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    private static final LocalDate VENDREDI = LocalDate.of(2026, 9, 11);
    private static final LocalDate SAMEDI = LocalDate.of(2026, 9, 12);
    private static final LocalDate LUNDI = LocalDate.of(2026, 9, 14);

    private BusinessCalendar calendrier(LocalDate... feries) {
        return new BusinessCalendar("CI", Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                                    Set.of(feries), LocalDate.of(2026, 1, 1),
                                    LocalDate.of(2027, 12, 31));
    }

    @Test
    @DisplayName("le week-end est parametre, pas presume")
    void the_weekend_is_configured() {
        var vendrediSamedi = new BusinessCalendar("XX",
            Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), Set.of(),
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));

        assertThat(vendrediSamedi.isBusinessDay(VENDREDI)).isFalse();
        assertThat(vendrediSamedi.isBusinessDay(LocalDate.of(2026, 9, 13))).isTrue();   // dimanche
    }

    @Test
    @DisplayName("un jour ferie n'est pas ouvre, meme en semaine")
    void a_holiday_is_not_a_business_day() {
        var avecFerie = calendrier(LUNDI);

        assertThat(avecFerie.isBusinessDay(LUNDI)).isFalse();
        assertThat(avecFerie.nextBusinessDay(VENDREDI)).isEqualTo(LUNDI.plusDays(1));
    }

    @Test
    @DisplayName("deux jours ouvres depuis un vendredi donnent le mardi, pas le dimanche")
    void two_business_days_from_friday_land_on_tuesday() {
        assertThat(calendrier().addBusinessDays(VENDREDI, 2)).isEqualTo(LUNDI.plusDays(1));
    }

    @Test
    @DisplayName("deux jours calendaires ajustes donnent le lundi : une journee d'ecart, et elle se facture")
    void two_calendar_days_adjusted_land_on_monday() {
        LocalDate calendaires = BusinessDayConvention.FOLLOWING.adjust(VENDREDI.plusDays(2),
                                                                      calendrier());
        assertThat(calendaires).isEqualTo(LUNDI);
        assertThat(calendrier().addBusinessDays(VENDREDI, 2)).isNotEqualTo(calendaires);
    }

    @Test
    @DisplayName("MODIFIED_FOLLOWING garde l'echeance dans son mois")
    void modified_following_keeps_the_month() {
        // 31 mai 2026 tombe un dimanche.
        LocalDate dimanche31mai = LocalDate.of(2026, 5, 31);
        assertThat(dimanche31mai.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);

        assertThat(BusinessDayConvention.FOLLOWING.adjust(dimanche31mai, calendrier()))
            .isEqualTo(LocalDate.of(2026, 6, 1));       // bascule d'exercice mensuel
        assertThat(BusinessDayConvention.MODIFIED_FOLLOWING.adjust(dimanche31mai, calendrier()))
            .isEqualTo(LocalDate.of(2026, 5, 29));      // reste en mai
    }

    @Test
    @DisplayName("le calendrier refuse de repondre au-dela de sa periode de saisie")
    void the_calendar_refuses_beyond_its_coverage() {
        assertThatThrownBy(() -> calendrier().isBusinessDay(LocalDate.of(2028, 1, 3)))
            .isInstanceOf(BusinessCalendar.CoverageException.class)
            .hasMessageContaining("les presumer ouvres produirait des dates de valeur fausses");
    }

    @Test
    @DisplayName("un pont de trois jours est traverse d'un coup")
    void a_long_weekend_is_crossed_at_once() {
        var avecPont = calendrier(LUNDI, LUNDI.plusDays(1));

        assertThat(avecPont.nextBusinessDay(VENDREDI)).isEqualTo(LUNDI.plusDays(2));
        assertThat(avecPont.addBusinessDays(VENDREDI, 1)).isEqualTo(LUNDI.plusDays(2));
        assertThat(avecPont.previousBusinessDayOrSame(SAMEDI)).isEqualTo(VENDREDI);
    }
}
