package io.corebanking.interest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.interest.rate.OverdraftRate;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.Reconciliation;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les interets courus finissent par etre regles : capitalises au client net de retenue, ou
 * preleves en agios taxe comprise. Et le sous-livre des positions se rapproche du grand livre.
 */
class InterestSettlementIT extends InterestTestBase {

    private static final LocalDate DEPART = BUSINESS_DATE;              // 13 septembre 2026
    private static final LocalDate FIN_TRIMESTRE = LocalDate.of(2026, 9, 30);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static void post(String key, LocalDate bookingDate, Account debit, Account credit,
                             String amount, LocalDate valueDate) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, bookingDate, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(debit.id(), xof(amount), valueDate, null),
                    PostingLine.credit(credit.id(), xof(amount), valueDate, null))));
    }

    /** Compte courant sans controle du disponible : il peut passer debiteur. */
    private static Account currentAccount(String code) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER,
                                      NormalBalance.CREDIT, Currencies.XOF, true, false, 1,
                                      AccountStatus.ACTIVE);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Money balance(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static List<Reconciliation.Discrepancy> positionsCheck() {
        return database.inTransaction(
            c -> new InterestReconciliation().run(c, ENTITY, FIN_TRIMESTRE, RUN));
    }

    @Test
    @DisplayName("la capitalisation trimestrielle credite le client du net, la retenue est reversee, les courus reviennent a zero")
    void quarterly_capitalisation_pays_net_of_withholding() {
        Account client = customer("CLI-600");
        Account caisse = gl("GL-CAISSE-600", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-600", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-600", NormalBalance.CREDIT);
        Account retenue = gl("GL-IRC-600", NormalBalance.CREDIT);
        database.inTransaction(c -> WithholdingTaxes.declare(
            c, ENTITY, "IRC", new BigDecimal("15"), retenue.id(), LocalDate.of(2026, 1, 1), null,
            ACTOR));
        post("dep-600", DEPART, caisse, client, "1000000", DEPART);

        InterestTerms terms = new InterestTerms(FlatRate.of("6"), DayCountConvention.ACT_365,
                                                AccrualSide.CREDITOR, charges.id(), courus.id(),
                                                SettlementTerms.capitalisation(Periodicity.QUARTERLY,
                                                                               "IRC"));
        // Dix-huit journees a 6 % sur 1 000 000 : 2 958,90 exact, 2 959 impute.
        interestService.accrueThrough(ENTITY, client.id(), FIN_TRIMESTRE,
                                      InterestTermsResolver.fixed(terms), FIN_TRIMESTRE, ACTOR, RUN);
        assertThat(balance(courus)).isEqualTo(xof("2959"));

        var settlement = new InterestSettlementService(database, postingService);
        Optional<InterestSettlementService.Settlement> done = settlement.settleOne(
            ENTITY, client.id(), FIN_TRIMESTRE, (a, d) -> terms, FIN_TRIMESTRE, ACTOR, RUN);

        assertThat(done).isPresent();
        assertThat(done.get().gross()).isEqualTo(xof("2959"));
        // 15 % de 2 959 = 443,85 : 444 retenus, 2 515 au client.
        assertThat(done.get().withholding()).isEqualTo(xof("444"));
        assertThat(done.get().net()).isEqualTo(xof("2515"));
        assertThat(balance(client)).isEqualTo(xof("1002515"));
        assertThat(balance(retenue)).isEqualTo(xof("444"));
        assertThat(balance(courus).isZero()).isTrue();

        // Le sous-livre sait ce qu'il a regle, et le grand livre est d'accord.
        database.inTransaction(c -> {
            var position = InterestPositions.load(c, client.id(), AccrualSide.CREDITOR,
                                                  Currencies.XOF);
            assertThat(position.settledTotal()).isEqualTo(xof("2959"));
            assertThat(position.settledThrough()).isEqualTo(FIN_TRIMESTRE);
            assertThat(position.unsettled().isZero()).isTrue();
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
        assertThat(positionsCheck()).isEmpty();

        // Le lendemain, les interets courent sur le capital augmente ; la periode reglee ne se
        // regle pas deux fois.
        LocalDate deuxOctobre = LocalDate.of(2026, 10, 2);
        interestService.accrueThrough(ENTITY, client.id(), deuxOctobre,
                                      InterestTermsResolver.fixed(terms), FIN_TRIMESTRE, ACTOR, RUN);
        assertThat(settlement.settleOne(ENTITY, client.id(), deuxOctobre, (a, d) -> terms,
                                        FIN_TRIMESTRE, ACTOR, RUN)).isEmpty();
        assertThat(positionsCheck()).isEmpty();
    }

    @Test
    @DisplayName("un arrete tombant un jour ferie regle les interets courus jusqu'a la fin de periode, pas jusqu'au jour du traitement")
    void settlement_stops_at_the_period_end_even_when_run_later() {
        Account client = customer("CLI-601");
        Account caisse = gl("GL-CAISSE-601", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-601", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-601", NormalBalance.CREDIT);
        post("dep-601", DEPART, caisse, client, "3650000", DEPART);

        InterestTerms terms = new InterestTerms(FlatRate.of("10"), DayCountConvention.ACT_365,
                                                AccrualSide.CREDITOR, charges.id(), courus.id(),
                                                SettlementTerms.capitalisation(Periodicity.MONTHLY,
                                                                               null));
        // 1 000 par jour : 18 000 au 30 septembre, 20 000 au 2 octobre.
        LocalDate deuxOctobre = LocalDate.of(2026, 10, 2);
        interestService.accrueThrough(ENTITY, client.id(), deuxOctobre,
                                      InterestTermsResolver.fixed(terms), FIN_TRIMESTRE, ACTOR, RUN);
        assertThat(balance(courus)).isEqualTo(xof("20000"));

        var done = new InterestSettlementService(database, postingService).settleOne(
            ENTITY, client.id(), deuxOctobre, (a, d) -> terms, FIN_TRIMESTRE, ACTOR, RUN);

        assertThat(done).isPresent();
        assertThat(done.get().periodEnd()).isEqualTo(FIN_TRIMESTRE);
        assertThat(done.get().gross()).isEqualTo(xof("18000"));
        // Les deux journees d'octobre restent en courus, et le sous-livre le sait.
        assertThat(balance(courus)).isEqualTo(xof("2000"));
        assertThat(positionsCheck()).isEmpty();
    }

    @Test
    @DisplayName("les agios courent dans l'autorisation et au-dela a deux taux, et sont arretes taxe comprise")
    void overdraft_interest_accrues_at_two_rates_and_is_charged_with_tax() {
        Account client = currentAccount("CLI-602");
        Account caisse = gl("GL-CAISSE-602", NormalBalance.DEBIT);
        Account agiosCourus = gl("GL-AGIOS-COURUS-602", NormalBalance.DEBIT);
        Account produits = gl("GL-PRODUITS-AGIOS-602", NormalBalance.CREDIT);
        Account taxe = gl("GL-TAF-602", NormalBalance.CREDIT);
        // Retrait de 500 000 sur un compte vide : 300 000 autorises, 200 000 de depassement.
        post("ret-602", DEPART, client, caisse, "500000", DEPART);

        InterestTerms terms = new InterestTerms(
            new OverdraftRate(new BigDecimal("300000"), new BigDecimal("12"), new BigDecimal("18")),
            DayCountConvention.ACT_365, AccrualSide.DEBTOR, agiosCourus.id(), produits.id(),
            SettlementTerms.overdraftCharge(Periodicity.MONTHLY, new BigDecimal("10"), taxe.id()));

        // Dix-huit jours : 300 000 x 12 % + 200 000 x 18 %, soit 72 000 l'an, 3 550,68 : 3 551.
        interestService.accrueThrough(ENTITY, client.id(), FIN_TRIMESTRE,
                                      InterestTermsResolver.fixed(terms), FIN_TRIMESTRE, ACTOR, RUN);
        assertThat(balance(agiosCourus)).isEqualTo(xof("3551"));
        assertThat(balance(produits)).isEqualTo(xof("3551"));

        var done = new InterestSettlementService(database, postingService).settleOne(
            ENTITY, client.id(), FIN_TRIMESTRE, (a, d) -> terms, FIN_TRIMESTRE, ACTOR, RUN);

        assertThat(done).isPresent();
        assertThat(done.get().gross()).isEqualTo(xof("3551"));
        assertThat(done.get().tax()).isEqualTo(xof("355"));
        assertThat(done.get().net()).isEqualTo(xof("3906"));
        assertThat(balance(client)).isEqualTo(xof("-503906"));
        assertThat(balance(agiosCourus).isZero()).isTrue();
        assertThat(balance(taxe)).isEqualTo(xof("355"));
        assertThat(positionsCheck()).isEmpty();
    }

    @Test
    @DisplayName("une retenue designee sans taux en vigueur bloque la capitalisation au lieu de payer brut")
    void a_withholding_without_rate_refuses_to_capitalise() {
        Account client = customer("CLI-603");
        Account caisse = gl("GL-CAISSE-603", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-603", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-603", NormalBalance.CREDIT);
        post("dep-603", DEPART, caisse, client, "1000000", DEPART);
        InterestTerms terms = new InterestTerms(FlatRate.of("6"), DayCountConvention.ACT_365,
                                                AccrualSide.CREDITOR, charges.id(), courus.id(),
                                                SettlementTerms.capitalisation(Periodicity.MONTHLY,
                                                                               "IRVM-ABSENTE"));
        interestService.accrueThrough(ENTITY, client.id(), FIN_TRIMESTRE,
                                      InterestTermsResolver.fixed(terms), FIN_TRIMESTRE, ACTOR, RUN);

        var service = new InterestSettlementService(database, postingService);
        assertThatThrownBy(() -> service.settleOne(ENTITY, client.id(), FIN_TRIMESTRE,
                                                   (a, d) -> terms, FIN_TRIMESTRE, ACTOR, RUN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("IRVM-ABSENTE")
            .hasMessageContaining("sans taux en vigueur");
        // Rien n'a bouge : ni le client, ni les courus.
        assertThat(balance(client)).isEqualTo(xof("1000000"));
        assertThat(balance(courus)).isEqualTo(xof("2959"));
    }

    @Test
    @DisplayName("une ecriture manuelle sur le compte de courus est un ecart de sous-livre, nomme")
    void a_manual_entry_on_the_accrued_account_is_a_named_discrepancy() {
        Account client = customer("CLI-604");
        Account caisse = gl("GL-CAISSE-604", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-604", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-604", NormalBalance.CREDIT);
        post("dep-604", DEPART, caisse, client, "1000000", DEPART);
        InterestTerms terms = new InterestTerms(FlatRate.of("6"), DayCountConvention.ACT_365,
                                                AccrualSide.CREDITOR, charges.id(), courus.id());
        interestService.accrueThrough(ENTITY, client.id(), DEPART.plusDays(9),
                                      InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR, RUN);
        assertThat(positionsCheck()).isEmpty();

        // La balance reste equilibree, seul le sous-livre voit l'ecart.
        post("manuel-604", BUSINESS_DATE, caisse, courus, "100", BUSINESS_DATE);

        List<Reconciliation.Discrepancy> ecarts = positionsCheck();
        assertThat(ecarts).hasSize(1);
        assertThat(ecarts.get(0).check()).isEqualTo(InterestReconciliation.CHECK);
        assertThat(ecarts.get(0).scope()).isEqualTo(courus.id().toString());
        assertThat(ecarts.get(0).gap()).isEqualByComparingTo("100");
        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });

        // L'ecart disparait avec l'ecriture qui le reprend : le controle ne garde pas de rancune.
        post("manuel-604-retour", BUSINESS_DATE, courus, caisse, "100", BUSINESS_DATE);
        assertThat(positionsCheck()).isEmpty();
    }

    @Test
    @DisplayName("l'annulation d'un traitement reconstruit la position depuis les journees restees actives")
    void cancelling_a_run_rebuilds_the_position() {
        Account client = customer("CLI-605");
        Account caisse = gl("GL-CAISSE-605", NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-605", NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-605", NormalBalance.CREDIT);
        post("dep-605", DEPART, caisse, client, "3650000", DEPART);
        InterestTerms terms = new InterestTerms(FlatRate.of("10"), DayCountConvention.ACT_365,
                                                AccrualSide.CREDITOR, charges.id(), courus.id());

        UUID premier = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        interestService.accrueThrough(ENTITY, client.id(), DEPART.plusDays(4),
                                      InterestTermsResolver.fixed(terms), BUSINESS_DATE, ACTOR,
                                      premier);
        AccrualOutcome suite = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(9), InterestTermsResolver.fixed(terms),
            BUSINESS_DATE, ACTOR, second);
        assertThat(balance(courus)).isEqualTo(xof("10000"));

        // Annulation du second traitement : son ecriture contre-passee, ses journees neutralisees.
        postingService.reverse(suite.entryId(), BUSINESS_DATE, BUSINESS_DATE,
                               IdempotencyKey.of("cancel-605"), "annulation");
        int rebuilt = database.inTransaction(c -> InterestPositions.cancelRun(c, second));
        assertThat(rebuilt).isEqualTo(1);

        database.inTransaction(c -> {
            var position = InterestPositions.load(c, client.id(), AccrualSide.CREDITOR,
                                                  Currencies.XOF);
            assertThat(position.accruedThrough()).isEqualTo(DEPART.plusDays(4));
            assertThat(position.postedTotal()).isEqualTo(xof("5000"));
            return null;
        });
        assertThat(balance(courus)).isEqualTo(xof("5000"));
        assertThat(positionsCheck()).isEmpty();

        // Et le calcul reprend exactement la ou il en etait.
        AccrualOutcome reprise = interestService.accrueThrough(
            ENTITY, client.id(), DEPART.plusDays(9), InterestTermsResolver.fixed(terms),
            BUSINESS_DATE, ACTOR, UUID.randomUUID());
        assertThat(reprise.from()).isEqualTo(DEPART.plusDays(5));
        assertThat(balance(courus)).isEqualTo(xof("10000"));
    }

    @Test
    @DisplayName("les fins de periode civiles : mois, trimestre, semestre, annee")
    void period_ends_follow_the_civil_calendar() {
        LocalDate d = LocalDate.of(2026, 11, 17);
        assertThat(SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.MONTHLY, d))
            .isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.QUARTERLY, d))
            .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.SEMIANNUAL, d))
            .isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.ANNUAL, d))
            .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(SettlementCalendar.lastPeriodEndOnOrBefore(Periodicity.QUARTERLY,
                                                              LocalDate.of(2026, 12, 31)))
            .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(SettlementCalendar.isPeriodEnd(Periodicity.MONTHLY, LocalDate.of(2028, 2, 29)))
            .isTrue();
        assertThatThrownBy(() -> SettlementTerms.capitalisation(Periodicity.DAILY, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unused")
    private static long count(String sql, UUID id) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, id);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("comptage", e);
            }
        });
    }
}
