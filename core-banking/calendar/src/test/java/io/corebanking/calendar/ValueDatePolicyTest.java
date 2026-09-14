package io.corebanking.calendar;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.interest.accrual.InterestBasis;
import io.corebanking.interest.accrual.InterestCalculator;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Direction;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValueDatePolicyTest {

    private static final LocalDate VENDREDI = LocalDate.of(2026, 9, 11);

    private final BusinessCalendar calendrier = new BusinessCalendar(
        "CI", Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(),
        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));

    private ValueDateRule rule(String type, String channel, Direction sens, int decalage,
                               OffsetUnit unite) {
        return new ValueDateRule(type, channel, sens, decalage, unite,
                                 BusinessDayConvention.FOLLOWING, LocalDate.of(2026, 1, 1), null);
    }

    @Test
    @DisplayName("une regle nommant un canal l'emporte sur la regle generale")
    void a_channel_rule_wins_over_the_general_one() {
        var policy = new ValueDatePolicy(calendrier, List.of(
            rule("DEPOSIT", null, Direction.CREDIT, 1, OffsetUnit.BUSINESS_DAYS),
            rule("DEPOSIT", "BRANCH", Direction.CREDIT, 0, OffsetUnit.BUSINESS_DAYS)));

        // Au guichet, le versement est disponible le jour meme.
        assertThat(policy.valueDateFor("DEPOSIT", "BRANCH", Direction.CREDIT, VENDREDI))
            .isEqualTo(VENDREDI);
        // Par un autre canal, la regle generale s'applique : lundi.
        assertThat(policy.valueDateFor("DEPOSIT", "MOBILE", Direction.CREDIT, VENDREDI))
            .isEqualTo(VENDREDI.plusDays(3));
    }

    @Test
    @DisplayName("l'absence de condition est un refus, jamais un repli sur la date comptable")
    void a_missing_rule_is_a_refusal() {
        var policy = new ValueDatePolicy(calendrier, List.of());

        assertThatThrownBy(() ->
            policy.valueDateFor("CHEQUE_REMITTANCE", "BRANCH", Direction.CREDIT, VENDREDI))
            .isInstanceOf(ValueDatePolicy.NoRuleException.class)
            .hasMessageContaining("resultat plausible et faux");
    }

    @Test
    @DisplayName("debit et credit ne se decalent pas de la meme facon : l'asymetrie est le sujet")
    void debit_and_credit_are_not_shifted_alike() {
        var policy = new ValueDatePolicy(calendrier, List.of(
            // Conditions de banque classiques : le retrait porte une date de valeur anterieure,
            // le versement une date posterieure. Les journees gagnees sont un produit.
            rule("WITHDRAWAL", null, Direction.DEBIT, -1, OffsetUnit.CALENDAR_DAYS),
            rule("DEPOSIT", null, Direction.CREDIT, 1, OffsetUnit.BUSINESS_DAYS)));

        LocalDate mercredi = LocalDate.of(2026, 9, 16);
        assertThat(policy.valueDateFor("WITHDRAWAL", "BRANCH", Direction.DEBIT, mercredi))
            .isEqualTo(mercredi.minusDays(1));
        assertThat(policy.valueDateFor("DEPOSIT", "BRANCH", Direction.CREDIT, mercredi))
            .isEqualTo(mercredi.plusDays(1));
    }

    @Test
    @DisplayName("deux jours de valeur sur un versement coutent 3 288 XOF au client, par operation")
    void two_value_days_have_a_measurable_price() {
        var favorable = new ValueDatePolicy(calendrier, List.of(
            rule("DEPOSIT", null, Direction.CREDIT, 0, OffsetUnit.BUSINESS_DAYS)));
        var defavorable = new ValueDatePolicy(calendrier, List.of(
            rule("DEPOSIT", null, Direction.CREDIT, 2, OffsetUnit.BUSINESS_DAYS)));

        LocalDate mercredi = LocalDate.of(2026, 9, 16);
        LocalDate tot = favorable.valueDateFor("DEPOSIT", null, Direction.CREDIT, mercredi);
        LocalDate tard = defavorable.valueDateFor("DEPOSIT", null, Direction.CREDIT, mercredi);
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(tot, tard)).isEqualTo(2);

        // 10 000 000 XOF a 6 % l'an, ACT/365 : chaque journee de valeur vaut 1 643,835 62 XOF.
        Money interetsTot = interets(tot, mercredi.plusDays(30));
        Money interetsTard = interets(tard, mercredi.plusDays(30));

        assertThat(interetsTot.minus(interetsTard).roundToCurrency())
            .isEqualTo(Money.of("3288", XOF));
        // Sur cent mille versements par mois, l'ecart depasse trois cents millions par an : le
        // parametrage des dates de valeur n'est pas un detail de mise en oeuvre.
    }

    private Money interets(LocalDate depuis, LocalDate jusqua) {
        List<DailyBalance> serie = new ArrayList<>();
        for (LocalDate jour = depuis; !jour.isAfter(jusqua); jour = jour.plusDays(1)) {
            serie.add(new DailyBalance(jour, Money.of("10000000", XOF)));
        }
        return InterestCalculator.accrue(serie, InterestBasis.DAILY_BALANCE, AccrualSide.CREDITOR,
                                         FlatRate.of("6"), DayCountConvention.ACT_365).total();
    }
}
