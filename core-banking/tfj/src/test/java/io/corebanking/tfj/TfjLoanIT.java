package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.loan.service.LoanStore;
import io.corebanking.product.ProductCatalog;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les echeances de credit dans la chaine complete du TFJ. */
class TfjLoanIT extends TfjTestBase {

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    @Test
    @DisplayName("le TFJ rend l'echeance exigible et la preleve : l'encours ne diminue qu'a ce moment")
    void echeancePrelevee() {
        Dossier dossier = dossier("L1", true);
        LocalDate jour = businessDate();

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(run.steps()).extracting(TfjRun.StepExecution::name).contains("LOAN_SCHEDULE");

        // 1 000 000 a 12 % sur douze mois : echeance de 88 849, dont 78 849 de capital.
        assertThat(soldeDe(dossier.courant())).isEqualTo(xof("911151"));
        assertThat(soldeDe(dossier.pret())).isEqualTo(xof("921151"));
        assertThat(soldeDe(dossier.produits())).isEqualTo(xof("10000"));
        assertThat(soldeDe(dossier.creances()).isZero()).isTrue();
    }

    @Test
    @DisplayName("l'annulation du TFJ rend l'echeance a nouveau exigible")
    void annulationRendLEcheanceExigible() {
        Dossier dossier = dossier("L2", false);
        LocalDate jour = businessDate();

        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(premier.isCompleted()).isTrue();
        assertThat(creancesOuvertes(dossier.contrat())).isEqualTo(2);

        engine.cancel(premier.id(), ACTOR, jour, "erreur de parametrage");

        // Sans reouverture, l'ecriture serait contre-passee et l'echeance resterait marquee comme
        // reclamee : le client n'aurait plus rien a payer pour ce mois, et aucun controle
        // comptable ne verrait l'ecart.
        assertThat(creancesOuvertes(dossier.contrat())).isZero();

        TfjRun second = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(second.isCompleted()).as(second.summary()).isTrue();
        assertThat(creancesOuvertes(dossier.contrat())).isEqualTo(2);
    }

    @Test
    @DisplayName("l'annulation du TFJ reprend l'interet de retard qu'il avait impute")
    void annulationReprendLInteretDeRetard() {
        Dossier dossier = dossier("L3", false);
        LocalDate jour = businessDate();

        // Premier jour : l'echeance devient exigible, rien n'est preleve.
        assertThat(engine.run(ENTITY, jour, ACTOR, RunMode.REAL).isCompleted()).isTrue();
        assertThat(interetDeRetard(dossier.contrat()).isZero()).isTrue();

        // Lendemain : l'impaye court.
        LocalDate lendemain = businessDate();
        TfjRun second = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);
        assertThat(second.isCompleted()).as(second.summary()).isTrue();
        Money couru = interetDeRetard(dossier.contrat());
        assertThat(couru.isPositive()).isTrue();

        engine.cancel(second.id(), ACTOR, lendemain, "erreur de parametrage");

        // La creance revient a zero : l'ecriture est contre-passee et le montant du avec elle.
        // Sans cette reprise, la creance resterait gonflee d'un montant dont plus aucune ecriture
        // ne rend compte, et la reconciliation ne le verrait pas — elle ne porte que sur le
        // journal.
        assertThat(interetDeRetard(dossier.contrat()).isZero()).isTrue();
    }

    // ------------------------------------------------------------------ outillage

    private record Dossier(UUID contrat, Account pret, Account courant, Account creances,
                           Account produits) {}

    /** Credit de 1 000 000 XOF a 12 % sur douze mois, premiere echeance a la journee traitee. */
    private static Dossier dossier(String code, boolean prelevementAutomatique) {
        LocalDate jour = businessDate();
        LocalDate deblocage = jour.minusDays(5);

        Account pret = account(code + "-PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT);
        Account courant = account(code + "-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Account creances = account(code + "-CREANCES", AccountKind.GL, NormalBalance.DEBIT);
        Account produits = account(code + "-PRODUITS", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account(code + "-TAXE", AccountKind.GL, NormalBalance.CREDIT);
        Account retard = account(code + "-RETARD", AccountKind.GL, NormalBalance.CREDIT);

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, creances.id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(LoanCatalog.P_DIRECT_DEBIT, String.valueOf(prelevementAutomatique));
        parametres.put(LoanCatalog.P_LATE_RATE, "18");
        parametres.put(LoanCatalog.P_LATE_INCOME, retard.id().toString());

        UUID contrat = database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "CRED-" + code, "TERM_LOAN", "Credit amortissable", "XOF",
                deblocage.minusDays(1), null, parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return LoanStore.createContract(c, new LoanStore.ContractDraft(
                ENTITY, "REF-" + code, "CRED-" + code, Currencies.XOF, pret.id(), courant.id(),
                xof("1000000"), deblocage, ACTOR));
        });

        loanService.disburse(contrat, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(deblocage).firstDueDate(jour).build()), ACTOR, APPROVER);

        return new Dossier(contrat, pret, courant, creances, produits);
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }

    private static Money interetDeRetard(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT COALESCE(SUM(original_amount), 0) FROM loan_receivable"
                + " WHERE contract_id = ? AND category = 'LATE_INTEREST' AND NOT cancelled")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return Money.of(rs.getBigDecimal(1), Currencies.XOF);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de l'interet de retard", e);
            }
        });
    }

    private static int creancesOuvertes(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM loan_receivable"
                + " WHERE contract_id = ? AND NOT cancelled AND outstanding > 0")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des creances", e);
            }
        });
    }
}
