package io.corebanking.ledger.domain;

import static io.corebanking.kernel.money.Currencies.EUR;
import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.error.InvalidPostingException;
import io.corebanking.ledger.domain.error.UnbalancedEntryException;
import io.corebanking.ledger.domain.posting.EntryValidator;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.ValidatedEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntryValidatorTest {

    private final Account client = Fixtures.customerAccount("CLI-001", XOF);
    private final Account caisse = Fixtures.glAccount("GL-CAISSE", XOF, NormalBalance.DEBIT);

    @Test
    @DisplayName("une ecriture equilibree est acceptee")
    void balanced_entry_is_accepted() {
        var entry = EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("250000", XOF), Fixtures.TODAY, "Versement"),
                PostingLine.credit(client.id(), Money.of("250000", XOF), Fixtures.TODAY, "Versement"))),
            Fixtures.context(XOF, client, caisse));

        assertThat(entry.lines()).hasSize(2);
        // Le compte client est de sens naturel CREDIT : un credit augmente son solde.
        assertThat(entry.lines().get(1).signedAmount()).isEqualTo(Money.of("250000", XOF));
        // La caisse est de sens naturel DEBIT : un debit augmente son solde.
        assertThat(entry.lines().get(0).signedAmount()).isEqualTo(Money.of("250000", XOF));
    }

    @Test
    @DisplayName("une ecriture desequilibree est refusee, et l'ecart est restitue")
    void unbalanced_entry_is_rejected() {
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("250000", XOF), Fixtures.TODAY, null),
                PostingLine.credit(client.id(), Money.of("249000", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, client, caisse)))
            .isInstanceOf(UnbalancedEntryException.class)
            .satisfies(e -> assertThat(((UnbalancedEntryException) e).imbalances())
                .containsEntry("XOF", Money.of("1000", XOF)));
    }

    @Test
    @DisplayName("une ecriture a une seule ligne n'est pas de la partie double")
    void single_line_entry_is_rejected() {
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("100", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, caisse)))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("partie double");
    }

    @Test
    @DisplayName("un montant XOF a decimales est refuse a la comptabilisation")
    void non_bookable_amount_is_rejected() {
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("100.50", XOF), Fixtures.TODAY, null),
                PostingLine.credit(client.id(), Money.of("100.50", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, client, caisse)))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("non comptabilisable");
    }

    @Test
    @DisplayName("un compte cloture n'accepte aucune imputation")
    void closed_account_is_rejected() {
        Account closed = new Account(client.id(), client.legalEntityId(), client.code(),
                                     client.kind(), client.normalBalance(), client.currency(),
                                     true, true, 1, AccountStatus.CLOSED);
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("100", XOF), Fixtures.TODAY, null),
                PostingLine.credit(closed.id(), Money.of("100", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, closed, caisse)))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("une operation de change s'equilibre par devise ET en contre-valeur")
    void fx_entry_balances_on_both_axes() {
        Account clientEur = Fixtures.customerAccount("CLI-EUR", EUR);
        Account positionEur = Fixtures.glAccount("GL-POSITION-EUR", EUR, NormalBalance.CREDIT);
        Account contrevaleur = Fixtures.glAccount("GL-CV-XOF", XOF, NormalBalance.DEBIT);
        BigDecimal parite = new BigDecimal("655.957");

        var entry = EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(clientEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, "Achat EUR")
                    .withFxRate(parite),
                PostingLine.credit(positionEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, "Position")
                    .withFxRate(parite),
                PostingLine.debit(contrevaleur.id(), Money.of("655957", XOF), Fixtures.TODAY, "CV"),
                PostingLine.credit(client.id(), Money.of("655957", XOF), Fixtures.TODAY, "Debit client"))),
            Fixtures.context(XOF, clientEur, positionEur, contrevaleur, client));

        assertThat(entry.lines()).hasSize(4);
        assertThat(entry.lines().get(0).functionalAmount()).isEqualTo(Money.of("655957", XOF));
    }

    @Test
    @DisplayName("une conversion directe a deux lignes est refusee si la contre-valeur ne tombe pas juste")
    void direct_cross_currency_conversion_is_rejected() {
        Account clientEur = Fixtures.customerAccount("CLI-EUR", EUR);

        // Le raccourci naif : on debite l'EUR et on credite le XOF, sans compte de position.
        // L'ecriture n'est equilibree dans aucune devise prise isolement ; seule la contre-valeur
        // peut l'etre, et uniquement si le cours est coherent avec les deux montants.
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(clientEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, null)
                    .withFxRate(new BigDecimal("650.000")),          // cours faux
                PostingLine.credit(client.id(), Money.of("655957", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, clientEur, client)))
            .isInstanceOf(UnbalancedEntryException.class)
            .satisfies(e -> assertThat(((UnbalancedEntryException) e).imbalances())
                .containsKeys("EUR", "XOF"));   // desequilibree dans chaque devise, des le 1er controle
    }

    @Test
    @DisplayName("des cours incoherents entre lignes d'une meme devise cassent la contre-valeur")
    void inconsistent_rates_within_a_currency_are_rejected() {
        Account clientEur = Fixtures.customerAccount("CLI-EUR", EUR);
        Account positionEur = Fixtures.glAccount("GL-POSITION-EUR", EUR, NormalBalance.CREDIT);
        Account contrevaleur = Fixtures.glAccount("GL-CV-XOF", XOF, NormalBalance.DEBIT);

        // Equilibree en EUR (1000 au debit, 1000 au credit) et en XOF, mais les deux lignes EUR
        // portent des cours differents : l'ecart de 5 957 XOF est une perte de change non
        // comptabilisee. Aucun controle par devise ne peut la voir.
        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(clientEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, null)
                    .withFxRate(new BigDecimal("655.957")),
                PostingLine.credit(positionEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, null)
                    .withFxRate(new BigDecimal("650.000")),
                PostingLine.debit(contrevaleur.id(), Money.of("655957", XOF), Fixtures.TODAY, null),
                PostingLine.credit(client.id(), Money.of("655957", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, clientEur, positionEur, contrevaleur, client)))
            .isInstanceOf(UnbalancedEntryException.class)
            .hasMessageContaining("contre-valeur");
    }

    @Test
    @DisplayName("un cours errone mais uniforme ne rompt aucun equilibre : le ledger ne peut pas le voir")
    void wrong_fx_rate_survives_a_symmetric_structure() {
        Account clientEur = Fixtures.customerAccount("CLI-EUR", EUR);
        Account positionEur = Fixtures.glAccount("GL-POSITION-EUR", EUR, NormalBalance.CREDIT);
        Account contrevaleur = Fixtures.glAccount("GL-CV-XOF", XOF, NormalBalance.DEBIT);

        // Les deux lignes en EUR portent le meme cours, donc des contre-valeurs egales et de sens
        // opposes : elles se compensent quel que soit ce cours. L'equilibre en contre-valeur est
        // structurellement satisfait et ne dit rien de la justesse du cours.
        var entry = EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(clientEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, null)
                    .withFxRate(new BigDecimal("650.000")),          // cours faux
                PostingLine.credit(positionEur.id(), Money.of("1000.00", EUR), Fixtures.TODAY, null)
                    .withFxRate(new BigDecimal("650.000")),
                PostingLine.debit(contrevaleur.id(), Money.of("655957", XOF), Fixtures.TODAY, null),
                PostingLine.credit(client.id(), Money.of("655957", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, clientEur, positionEur, contrevaleur, client));

        assertThat(entry.lines()).hasSize(4);
        // Le ledger ne peut pas rejeter cette ecriture : le controle du cours appartient au
        // referentiel des cours, confronte a la table de reference et a ses tolerances.
        assertThat(entry.lines().get(0).functionalAmount()).isEqualTo(Money.of("650000", XOF));
    }

    @Test
    @DisplayName("un compte a controle de solde ne peut pas etre reparti sur plusieurs stripes")
    void controlled_account_cannot_be_striped() {
        assertThatThrownBy(() -> new Account(java.util.UUID.randomUUID(), Fixtures.ENTITY, "CLI-X",
            io.corebanking.ledger.domain.account.AccountKind.CUSTOMER, NormalBalance.CREDIT,
            XOF, true, true, 32, AccountStatus.ACTIVE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compte chaud");
    }

    @Test
    @DisplayName("une ecriture ne melange pas le bilan et le hors bilan ; entre comptes de hors bilan, elle est marquee comme telle")
    void an_entry_keeps_to_one_world() {
        Account engagement = Fixtures.glAccount("GL-ENGAGEMENT", XOF, NormalBalance.DEBIT)
            .withNature(AccountNature.OFF_BALANCE_SHEET);
        Account contrepartie = Fixtures.glAccount("GL-CONTREPARTIE-HB", XOF, NormalBalance.CREDIT)
            .withNature(AccountNature.OFF_BALANCE_SHEET);

        assertThatThrownBy(() -> EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(engagement.id(), Money.of("500000", XOF), Fixtures.TODAY, null),
                PostingLine.credit(caisse.id(), Money.of("500000", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, engagement, caisse)))
            .isInstanceOf(InvalidPostingException.class)
            .hasMessageContaining("hors bilan");

        ValidatedEntry engagementDonne = EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(engagement.id(), Money.of("500000", XOF), Fixtures.TODAY, null),
                PostingLine.credit(contrepartie.id(), Money.of("500000", XOF), Fixtures.TODAY,
                                   null))),
            Fixtures.context(XOF, engagement, contrepartie));
        assertThat(engagementDonne.offBalance()).isTrue();

        ValidatedEntry versement = EntryValidator.validate(
            Fixtures.command(List.of(
                PostingLine.debit(caisse.id(), Money.of("1000", XOF), Fixtures.TODAY, null),
                PostingLine.credit(client.id(), Money.of("1000", XOF), Fixtures.TODAY, null))),
            Fixtures.context(XOF, client, caisse));
        assertThat(versement.offBalance()).isFalse();
    }
}
