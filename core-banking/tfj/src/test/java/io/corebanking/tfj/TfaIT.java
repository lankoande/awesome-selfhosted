package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La cloture annuelle : le resultat determine, le dernier mois et l'exercice clos ; l'annulation defait tout. */
class TfaIT extends TfjTestBase {

    private static final LocalDate FIN_EXERCICE = LocalDate.of(2026, 9, 30);
    private static final LocalDate DEBUT_EXERCICE = LocalDate.of(2025, 10, 1);

    private static TfjEngine tfa() {
        return StandardTfa.engine(database, postingService, calendar);
    }

    private static TfjEngine tfm() {
        return StandardTfm.engine(database, postingService, calendar);
    }

    private static void arreterJusquAu(LocalDate inclus) {
        while (!businessDate().isAfter(inclus)) {
            TfjRun run = engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
            assertThat(run.isCompleted()).as(run.summary()).isTrue();
        }
    }

    private static Account compteDeResultat(String code, NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, normal,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE)
            .withNature(AccountNature.PROFIT_AND_LOSS);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Money solde(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static String statutPeriode(LocalDate date) {
        return database.inTransaction(c -> Entities.periodStatus(c, ENTITY, date)).orElse("?");
    }

    private static String statutExercice() {
        return database.inTransaction(
            c -> FiscalYears.endingOn(c, ENTITY, FIN_EXERCICE)).orElseThrow().status();
    }

    private static void ecriture(String key, Account debit, Account credit, String montant,
                                 LocalDate date) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), Money.of(montant, Currencies.XOF), date, null),
                    PostingLine.credit(credit.id(), Money.of(montant, Currencies.XOF), date,
                                       null))));
    }

    @Test
    @DisplayName("la cloture annuelle solde les comptes de resultat sur le compte de resultat, clot le mois et l'exercice ; son annulation defait tout, a la date de fin d'exercice")
    void the_year_is_closed_then_reopened() {
        // Des periodes mensuelles, comme en exploitation, et un exercice qui finit avec septembre.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE accounting_period SET end_date = ? WHERE legal_entity_id = ?"
                + " AND start_date = ?")) {
                ps.setObject(1, FIN_EXERCICE);
                ps.setObject(2, ENTITY);
                ps.setObject(3, LocalDate.of(2026, 9, 1));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Periode de septembre", e);
            }
            Entities.openPeriod(c, ENTITY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
            return null;
        });
        Account resultat = account("RESULTAT-TFA", AccountKind.GL, NormalBalance.CREDIT);
        database.inTransaction(c -> FiscalYears.open(c, ENTITY, DEBUT_EXERCICE, FIN_EXERCICE,
                                                     resultat.id(), ACTOR, APPROVER));
        // Le compte de resultat de l'exercice ne peut pas etre lui-meme un compte de resultat.
        Account produitsTiers = compteDeResultat("PRODUITS-X", NormalBalance.CREDIT);
        assertThatThrownBy(() -> database.inTransaction(c -> FiscalYears.open(
                c, ENTITY, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30),
                produitsTiers.id(), ACTOR, APPROVER)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bilan");

        Account caisse = account("CAISSE-TFA", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = compteDeResultat("CHARGES-TFA", NormalBalance.DEBIT);
        Account produits = compteDeResultat("PRODUITS-TFA", NormalBalance.CREDIT);
        Account courus = account("COURUS-TFA", AccountKind.GL, NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-TFA", charges, courus, "EP-TFA");
        deposit(client, caisse, "10000000", J1, "dep-tfa");
        ecriture("produit-tfa", caisse, produits, "1500", J1);

        arreterJusquAu(FIN_EXERCICE);
        assertThat(businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        Money chargesAvant = solde(charges);
        Money produitsAvant = solde(produits);
        Money caisseAvant = solde(caisse);
        assertThat(chargesAvant.isPositive()).as("des interets ont couru").isTrue();
        assertThat(produitsAvant).isEqualTo(Money.of("1500", Currencies.XOF));

        // Le dernier mois d'un exercice ne se clot pas par un arrete mensuel.
        assertThatThrownBy(() -> tfm().run(ENTITY, FIN_EXERCICE, ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("cloture annuelle");
        // Et la cloture annuelle porte la date de fin d'un exercice.
        assertThatThrownBy(() -> tfa().run(ENTITY, LocalDate.of(2026, 9, 15), ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("Aucun exercice");

        TfjRun cloture = tfa().run(ENTITY, FIN_EXERCICE, ACTOR, RunMode.REAL);

        assertThat(cloture.isCompleted()).as(cloture.summary()).isTrue();
        assertThat(cloture.steps()).extracting(TfjRun.StepExecution::name)
            .containsExactly("MONTH_COMPLETE", "YEAR_COMPLETE", "RESULT_DETERMINATION",
                             "FULL_RECONCILIATION", "PERIOD_CLOSE", "FISCAL_YEAR_CLOSE");
        // Les comptes de resultat sont soldes ; le compte de resultat porte le resultat ;
        // le bilan n'a pas bouge.
        assertThat(solde(charges).isZero()).isTrue();
        assertThat(solde(produits).isZero()).isTrue();
        assertThat(solde(resultat)).isEqualTo(produitsAvant.minus(chargesAvant));
        assertThat(solde(caisse)).isEqualTo(caisseAvant);
        assertThat(statutPeriode(FIN_EXERCICE)).isEqualTo("CLOSED");
        assertThat(statutExercice()).isEqualTo("CLOSED");
        assertThat(businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        // Redemander la cloture rend le rapport existant.
        assertThat(tfa().run(ENTITY, FIN_EXERCICE, ACTOR, RunMode.REAL).id())
            .isEqualTo(cloture.id());

        // L'annulation se date de la fin d'exercice, dans la periode rouverte pour cela.
        assertThatThrownBy(() -> tfa().cancel(cloture.id(), ACTOR, LocalDate.of(2026, 10, 1),
                                              "produit oublie"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("fin d'exercice");
        tfa().cancel(cloture.id(), ACTOR, FIN_EXERCICE, "produit oublie");

        assertThat(solde(charges)).isEqualTo(chargesAvant);
        assertThat(solde(produits)).isEqualTo(produitsAvant);
        assertThat(solde(resultat).isZero()).isTrue();
        assertThat(statutPeriode(FIN_EXERCICE)).isEqualTo("REOPENED");
        assertThat(statutExercice()).isEqualTo("REOPENED");

        // Le produit oublie est comptabilise en septembre, et la cloture rejouee le prend.
        ecriture("produit-oublie", caisse, produits, "500", FIN_EXERCICE);
        TfjRun rejouee = tfa().run(ENTITY, FIN_EXERCICE, ACTOR, RunMode.REAL);
        assertThat(rejouee.isCompleted()).as(rejouee.summary()).isTrue();
        assertThat(solde(resultat))
            .isEqualTo(produitsAvant.plus(Money.of("500", Currencies.XOF)).minus(chargesAvant));
        assertThat(solde(produits).isZero()).isTrue();
        assertThat(statutExercice()).isEqualTo("CLOSED");
    }
}
