package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.PrepaymentMode;
import io.corebanking.loan.ScheduleGenerator;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Interets courus non echus sur credits : l'interet de l'echeance est reconnu jour apres jour, et
 * le sous-livre du credit se rapproche du grand livre.
 */
class LoanInterestAccrualIT extends LoanTestBase {

    /** 1 000 000 a 12 % sur douze mois : premiere echeance de 88 849, dont 10 000 d'interets. */
    private static AmortisationSchedule echeancier() {
        return ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }

    private static List<Reconciliation.Discrepancy> sousLivre(Decor decor, LocalDate date) {
        return database.inTransaction(
            c -> new LoanReconciliation().run(c, decor.entityId(), date, null));
    }

    @Test
    @DisplayName("l'interet de l'echeance est etale sur les jours de sa periode, et repris a l'echeance")
    void etalementPuisReprise() {
        Decor decor = decor("I1");
        product(decor, "CRED-I1", Map.of());
        UUID contrat = contract(decor, "REF-I1", "CRED-I1", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);

        // Periode du 15 septembre au 15 octobre : 31 jours. Au 24 septembre, 10 jours ecoules :
        // 10 000 x 10 / 31 = 3 225,81, soit 3 226 constates en produits.
        var dixJours = accrue(decor, DEBLOCAGE.plusDays(9));
        assertThat(dixJours.anomalies()).isEmpty();
        assertThat(dixJours.linesAccrued()).isEqualTo(1);
        assertThat(solde(decor.courus())).isEqualTo(xof("3226"));
        assertThat(solde(decor.produitsInterets())).isEqualTo(xof("3226"));
        assertThat(sousLivre(decor, DEBLOCAGE.plusDays(9))).isEmpty();

        // Un rattrapage de plusieurs journees ne change rien au cumul : 20 jours, 6 452.
        accrue(decor, DEBLOCAGE.plusDays(19));
        assertThat(solde(decor.courus())).isEqualTo(xof("6452"));

        // A l'echeance : la creance reprend 10 000 des courus, l'etalement du jour les complete.
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        accrue(decor, PREMIERE_ECHEANCE);
        assertThat(solde(decor.courus()).isZero()).isTrue();
        assertThat(solde(decor.produitsInterets())).isEqualTo(xof("10000"));
        assertThat(solde(decor.creances())).isEqualTo(xof("10000"));
        assertThat(sousLivre(decor, PREMIERE_ECHEANCE)).isEmpty();

        // Le lendemain, la deuxieme echeance commence : 9 212 sur 31 jours, 297 le premier jour.
        accrue(decor, PREMIERE_ECHEANCE.plusDays(1));
        assertThat(solde(decor.courus())).isEqualTo(xof("297"));
        assertThat(sousLivre(decor, PREMIERE_ECHEANCE.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("rejouer l'etalement de la meme journee ne constate rien deux fois")
    void idempotence() {
        Decor decor = decor("I2");
        product(decor, "CRED-I2", Map.of());
        UUID contrat = contract(decor, "REF-I2", "CRED-I2", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);

        accrue(decor, DEBLOCAGE.plusDays(9));
        var rejeu = accrue(decor, DEBLOCAGE.plusDays(9));

        assertThat(rejeu.linesAccrued()).isZero();
        assertThat(solde(decor.courus())).isEqualTo(xof("3226"));
    }

    @Test
    @DisplayName("un echeancier remplace emporte ses courus : le nouveau repart de sa premiere periode")
    void echeancierRemplace() {
        Decor decor = decor("I3");
        product(decor, "CRED-I3", Map.of());
        UUID contrat = contract(decor, "REF-I3", "CRED-I3", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);
        accrue(decor, DEBLOCAGE.plusDays(9));
        assertThat(solde(decor.courus())).isEqualTo(xof("3226"));

        // Remboursement anticipe partiel le 25 septembre : l'echeancier est reconstruit.
        alimenter(decor, "500000", DEBLOCAGE.plusDays(10), "alim-I3");
        loanService.prepay(contrat, xof("400000"), PrepaymentMode.REDUCE_INSTALMENT,
                           DEBLOCAGE.plusDays(10), IdempotencyKey.of("prepay-I3"), ACTOR,
                           APPROVER);

        var lendemain = accrue(decor, DEBLOCAGE.plusDays(11));

        // Les 3 226 de l'ancienne echeance sont repris ; la nouvelle premiere periode commence
        // le 26 septembre et n'a qu'un jour de couru.
        assertThat(lendemain.anomalies()).isEmpty();
        assertThat(solde(decor.courus()).isLessThan(xof("3226"))).isTrue();
        assertThat(sousLivre(decor, DEBLOCAGE.plusDays(11))).isEmpty();
        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, decor.entityId())).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("l'annulation du traitement neutralise ses journees, et l'etalement reprend exactement")
    void annulation() {
        Decor decor = decor("I4");
        product(decor, "CRED-I4", Map.of());
        UUID contrat = contract(decor, "REF-I4", "CRED-I4", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);

        UUID run = UUID.randomUUID();
        var service = new LoanInterestAccrualService(database, postingService);
        service.accrue(decor.entityId(), DEBLOCAGE.plusDays(9), ACTOR, run);
        assertThat(solde(decor.courus())).isEqualTo(xof("3226"));

        // Ce que le moteur d'arrete fait a l'annulation : contre-passer, puis neutraliser.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT id, booking_date FROM journal_entry WHERE batch_run_id = ?")) {
                ps.setObject(1, run);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        postingService.reverse(rs.getObject(1, UUID.class),
                                               rs.getObject(2, LocalDate.class),
                                               DEBLOCAGE.plusDays(9),
                                               IdempotencyKey.of("cancel-I4-" + rs.getString(1)),
                                               "annulation");
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            LoanStore.cancelInterestAccruals(c, run);
            return null;
        });
        assertThat(solde(decor.courus()).isZero()).isTrue();
        assertThat(sousLivre(decor, DEBLOCAGE.plusDays(9))).isEmpty();

        accrue(decor, DEBLOCAGE.plusDays(9));
        assertThat(solde(decor.courus())).isEqualTo(xof("3226"));
    }

    @Test
    @DisplayName("un ecart entre le sous-livre du credit et le grand livre est nomme : creances, encours, courus")
    void ecartsNommes() {
        Decor decor = decor("I5");
        product(decor, "CRED-I5", Map.of());
        UUID contrat = contract(decor, "REF-I5", "CRED-I5", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        accrue(decor, PREMIERE_ECHEANCE.plusDays(1));
        assertThat(sousLivre(decor, PREMIERE_ECHEANCE.plusDays(1))).isEmpty();

        // Trois ecritures manuelles, equilibrees, que la balance ne voit pas.
        manuel(decor, decor.creances(), decor.caisse(), "1", PREMIERE_ECHEANCE.plusDays(1));
        manuel(decor, decor.pret(), decor.caisse(), "2", PREMIERE_ECHEANCE.plusDays(1));
        manuel(decor, decor.courus(), decor.caisse(), "3", PREMIERE_ECHEANCE.plusDays(1));

        List<Reconciliation.Discrepancy> ecarts = sousLivre(decor, PREMIERE_ECHEANCE.plusDays(1));
        assertThat(ecarts).extracting(Reconciliation.Discrepancy::check)
            .containsExactlyInAnyOrder(LoanReconciliation.CHECK_RECEIVABLES,
                                       LoanReconciliation.CHECK_OUTSTANDING,
                                       LoanReconciliation.CHECK_ACCRUED);
        assertThat(ecarts).extracting(d -> d.gap().intValue()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(ecarts).filteredOn(d -> d.check().equals(LoanReconciliation.CHECK_OUTSTANDING))
            .extracting(Reconciliation.Discrepancy::scope).containsExactly("REF-I5");
        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, decor.entityId())).isEmpty();
            return null;
        });
    }

    private static void manuel(Decor decor, io.corebanking.ledger.domain.account.Account debit,
                               io.corebanking.ledger.domain.account.Account credit, String montant,
                               LocalDate date) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("manuel-" + debit.code() + "-" + montant), decor.entityId(), date,
            "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of(montant, Currencies.XOF), date, null),
                    PostingLine.credit(credit.id(), Money.of(montant, Currencies.XOF), date,
                                       null))));
    }
}
