package io.corebanking.ledger.store;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le ledger est bitemporel : date comptable et instant de connaissance sont deux axes
 * independants.
 *
 * <p>Le scenario reproduit la situation qui met en difficulte tous les progiciels etablis : un etat
 * arrete en fin de mois, puis regenere plus tard, ne redonne pas le meme chiffre parce que des
 * ecritures antidatees sont arrivees entre-temps. La question posee en inspection n'est pas
 * « quel est le bon chiffre » mais « pourquoi les deux different », et c'est a celle-la qu'un
 * ledger mono-temporel ne sait pas repondre.
 */
class BitemporalIT extends LedgerTestBase {

    @Test
    @DisplayName("un etat arrete reste reproductible apres l'arrivee d'ecritures antidatees")
    void an_issued_statement_stays_reproducible() {
        Account client = newCustomerAccount("CLI-200", XOF);
        Account caisse = newGlAccount("GL-CAISSE-200", XOF, NormalBalance.DEBIT, 1);
        LocalDate arrete = BUSINESS_DATE.minusMonths(1).withDayOfMonth(28);

        // 1. Le mois se deroule : deux operations, connues au moment de l'arrete.
        PostingResult op1 = post(client, caisse, "500000", arrete.minusDays(10), "bt-200-1");
        PostingResult op2 = post(client, caisse, "300000", arrete.minusDays(3),  "bt-200-2");

        var instantDeLArrete = op2.knowledgeTime();

        // 2. L'etat est edite : 800 000 XOF.
        Money soldeEdite = database.inTransaction(c ->
            Balances.asKnownAt(c, client.id(), arrete, instantDeLArrete).balance());
        assertThat(soldeEdite).isEqualTo(Money.of("800000", XOF));

        // 3. Plus tard, une operation antidatee arrive : sa date comptable est anterieure a
        //    l'arrete, mais elle n'etait pas connue quand l'etat a ete produit.
        post(client, caisse, "150000", arrete.minusDays(5), "bt-200-3");

        // 4. Le solde courant a change...
        Money soldeAujourdhui = database.inTransaction(c ->
            Balances.replayAsOfBookingDate(c, client.id(), arrete));
        assertThat(soldeAujourdhui).isEqualTo(Money.of("950000", XOF));

        // 5. ... mais l'etat edite reste reproductible a l'identique, des annees apres.
        Money soldeRegenere = database.inTransaction(c ->
            Balances.asKnownAt(c, client.id(), arrete, instantDeLArrete).balance());
        assertThat(soldeRegenere).isEqualTo(soldeEdite);

        // 6. Et l'ecart entre les deux s'explique exactement : 150 000 XOF arrives apres coup.
        assertThat(soldeAujourdhui.minus(soldeRegenere)).isEqualTo(Money.of("150000", XOF));
    }

    @Test
    @DisplayName("date de valeur et date comptable donnent deux soldes differents, tous deux justes")
    void value_date_and_booking_date_are_distinct_axes() {
        Account client = newCustomerAccount("CLI-201", XOF);
        Account caisse = newGlAccount("GL-CAISSE-201", XOF, NormalBalance.DEBIT, 1);

        // Operation comptabilisee aujourd'hui, mais avec une date de valeur a J+2 : le client est
        // credite en comptabilite, sans que les fonds portent interet avant J+2.
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("bt-201"), ENTITY, BUSINESS_DATE, "TRANSFER_IN", ACTOR,
            List.of(
                PostingLine.debit(caisse.id(), Money.of("400000", XOF),
                                  BUSINESS_DATE.plusDays(2), "Virement recu"),
                PostingLine.credit(client.id(), Money.of("400000", XOF),
                                   BUSINESS_DATE.plusDays(2), "Virement recu"))));

        database.inTransaction(c -> {
            // En date comptable, l'operation est acquise aujourd'hui.
            assertThat(Balances.replayAsOfBookingDate(c, client.id(), BUSINESS_DATE))
                .isEqualTo(Money.of("400000", XOF));
            // En date de valeur — la seule base licite de calcul des interets — elle ne compte pas
            // encore. Confondre les deux, c'est facturer des agios faux.
            assertThat(Balances.replayAsOfValueDate(c, client.id(), BUSINESS_DATE))
                .isEqualTo(Money.zero(XOF));
            assertThat(Balances.replayAsOfValueDate(c, client.id(), BUSINESS_DATE.plusDays(2)))
                .isEqualTo(Money.of("400000", XOF));
            return null;
        });
    }

    private PostingResult post(Account client, Account caisse, String amount,
                               LocalDate bookingDate, String key) {
        return postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, bookingDate, "DEPOSIT", ACTOR,
            List.of(PostingLine.debit(caisse.id(), Money.of(amount, XOF), bookingDate, null),
                    PostingLine.credit(client.id(), Money.of(amount, XOF), bookingDate, null))));
    }
}
