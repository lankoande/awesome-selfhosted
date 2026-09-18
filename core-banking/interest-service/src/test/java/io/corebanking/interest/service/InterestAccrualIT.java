package io.corebanking.interest.service;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.accrual.DailyBalance;
import io.corebanking.interest.accrual.InterestBasis;
import io.corebanking.interest.accrual.InterestCalculator;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Reconciliation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterestAccrualIT extends InterestTestBase {

    private static final LocalDate DEPART = BUSINESS_DATE;

    private InterestTerms terms(Account charges, Account courus, String rate) {
        return new InterestTerms(FlatRate.of(rate), DayCountConvention.ACT_365,
                                 AccrualSide.CREDITOR, charges.id(), courus.id());
    }

    private void deposit(Account client, Account caisse, String amount, LocalDate valueDate,
                         String key) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, BUSINESS_DATE, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), valueDate, "Versement"),
                    PostingLine.credit(client.id(), Money.of(amount, XOF), valueDate, "Versement"))));
    }

    @Test
    @DisplayName("365 jours d'accruals quotidiens ne derivent pas : 42 000 XOF, pas 41 975")
    void daily_accrual_over_a_year_does_not_drift() {
        Account client = customer("CLI-400");
        Account caisse = gl("GL-CAISSE-400", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-INT-400", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-400", NormalBalance.CREDIT);
        deposit(client, caisse, "1200000", DEPART, "dep-400");

        InterestTerms terms = terms(charges, courus, "3.5");
        AccrualOutcome last = null;
        for (int day = 0; day < 365; day++) {
            last = interestService.accrueThrough(ENTITY, client.id(), DEPART.plusDays(day),
                                                 InterestTermsResolver.fixed(terms), BUSINESS_DATE,
                                                 ACTOR, RUN);
        }

        // Le cumul exact est 41 999,998 85 ; arrondi une seule fois, il donne 42 000.
        assertThat(last).isNotNull();
        assertThat(last.postedTotal()).isEqualTo(Money.of("42000", XOF));

        // Arrondir chaque journee aurait donne 365 x 115 = 41 975, soit 25 XOF de moins.
        assertThat(last.postedTotal().minus(Money.of("41975", XOF))).isEqualTo(Money.of("25", XOF));

        database.inTransaction(c -> {
            // Le compte d'interets courus porte exactement le montant impute.
            assertThat(Balances.current(c, courus.id())).isEqualTo(Money.of("42000", XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("chaque imputation reste un entier, et le cumul ne s'ecarte jamais d'une unite")
    void every_posting_is_an_integer_and_cumulative_never_drifts() {
        Account client = customer("CLI-401");
        Account caisse = gl("GL-CAISSE-401", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-INT-401", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-401", NormalBalance.CREDIT);
        deposit(client, caisse, "1200000", DEPART, "dep-401");

        InterestTerms terms = terms(charges, courus, "3.5");
        for (int day = 0; day < 60; day++) {
            AccrualOutcome outcome = interestService.accrueThrough(
                ENTITY, client.id(), DEPART.plusDays(day), InterestTermsResolver.fixed(terms),
                BUSINESS_DATE, ACTOR, RUN);

            // Aucun montant a decimales n'atteint jamais le journal.
            assertThat(outcome.postedDelta().isBookable()).isTrue();
            // Et le cumul impute colle au cumul exact a moins d'une unite pres, en permanence.
            assertThat(outcome.postedTotal().minus(outcome.cumulativePrecise()).abs()
                              .isLessThan(Money.of("1", XOF))).isTrue();
        }
    }

    @Test
    @DisplayName("une operation antidatee declenche le recalcul retroactif des interets")
    void an_antedated_entry_triggers_retroactive_recompute() {
        Account client = customer("CLI-402");
        Account caisse = gl("GL-CAISSE-402", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-INT-402", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-402", NormalBalance.CREDIT);
        InterestTerms terms = terms(charges, courus, "6");

        // 1. Un mois remunere sur 1 000 000.
        deposit(client, caisse, "1000000", DEPART, "dep-402-1");
        AccrualOutcome avant = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(29), InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);
        assertThat(avant.produced()).isTrue();

        // 2. Une operation arrive apres coup, avec une date de valeur au 11e jour.
        deposit(client, caisse, "500000", DEPART.plusDays(10), "dep-402-2");

        // 3. Sans recalcul, les interets des 20 dernieres journees sont faux. On recalcule.
        AccrualOutcome apres = interestService.recomputeFrom(
            ENTITY, client.id(), DEPART.plusDays(10), InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);

        // Le resultat doit coincider avec un calcul mene d'un seul tenant sur la serie corrigee.
        List<DailyBalance> serieCorrigee = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            String solde = day < 10 ? "1000000" : "1500000";
            serieCorrigee.add(new DailyBalance(DEPART.plusDays(day), Money.of(solde, XOF)));
        }
        Money attendu = InterestCalculator.accrue(serieCorrigee, InterestBasis.DAILY_BALANCE,
            AccrualSide.CREDITOR, FlatRate.of("6"), DayCountConvention.ACT_365).total();

        assertThat(apres.cumulativePrecise()).isEqualTo(attendu);
        assertThat(apres.postedTotal()).isEqualTo(attendu.roundToCurrency());

        database.inTransaction(c -> {
            // Le solde du compte d'interets courus reflete le montant corrige, pas la somme des
            // deux calculs : l'ecriture d'origine a bien ete contre-passee.
            assertThat(Balances.current(c, courus.id())).isEqualTo(attendu.roundToCurrency());
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("le recalcul conserve l'historique : on peut toujours reconstituer ce qui a ete facture")
    void recompute_keeps_the_previous_generation() {
        Account client = customer("CLI-403");
        Account caisse = gl("GL-CAISSE-403", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-INT-403", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-403", NormalBalance.CREDIT);
        InterestTerms terms = terms(charges, courus, "6");

        deposit(client, caisse, "1000000", DEPART, "dep-403-1");
        interestService.accrueThrough(ENTITY, client.id(), DEPART.plusDays(19),
                                      InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);
        deposit(client, caisse, "500000", DEPART.plusDays(5), "dep-403-2");
        interestService.recomputeFrom(ENTITY, client.id(), DEPART.plusDays(5),
                                      InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);

        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT generation, status, count(*) FROM interest_accrual"
                + " WHERE account_id = ? GROUP BY generation, status"
                + " ORDER BY generation, status")) {
                ps.setObject(1, client.id());
                try (var rs = ps.executeQuery()) {
                    // Journees 0 a 4 : generation 1, toujours actives, jamais recalculees.
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                    assertThat(rs.getString(2)).isEqualTo("ACTIVE");
                    assertThat(rs.getInt(3)).isEqualTo(5);

                    // Journees 5 a 19 : generation 1 neutralisee, conservee pour l'audit.
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                    assertThat(rs.getString(2)).isEqualTo("REVERSED");
                    assertThat(rs.getInt(3)).isEqualTo(15);

                    // Les memes journees recalculees en generation 2.
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                    assertThat(rs.getString(2)).isEqualTo("ACTIVE");
                    assertThat(rs.getInt(3)).isEqualTo(15);
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("rejouer un calcul d'interets ne produit pas de seconde ecriture")
    void accrual_is_idempotent() {
        Account client = customer("CLI-404");
        Account caisse = gl("GL-CAISSE-404", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-INT-404", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-404", NormalBalance.CREDIT);
        deposit(client, caisse, "2000000", DEPART, "dep-404");

        InterestTerms terms = terms(charges, courus, "5");
        AccrualOutcome premier = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(9), InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);
        // Une seconde execution sur la meme periode ne trouve plus de journee a remunerer.
        AccrualOutcome second = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(9), InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);

        assertThat(premier.produced()).isTrue();
        assertThat(second.produced()).isFalse();
        database.inTransaction(c -> {
            assertThat(Balances.current(c, courus.id())).isEqualTo(premier.postedTotal());
            return null;
        });
    }
}
