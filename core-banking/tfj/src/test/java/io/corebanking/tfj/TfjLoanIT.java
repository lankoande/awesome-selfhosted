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
    @DisplayName("le credit solde est clos par l'arrete, et l'annulation de l'arrete le rouvre")
    void clotureParLArreteEtReouverture() {
        // Une seule echeance : 1 010 000. Le deblocage en a apporte 1 000 000, le reste est verse.
        Dossier dossier = dossier("L5", true, Map.of(), 1);
        Account caisse = account("L5-CAISSE", AccountKind.GL, NormalBalance.DEBIT);
        LocalDate jour = businessDate();
        deposit(dossier.courant(), caisse, "20000", jour, "dep-L5");

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(run.steps()).extracting(TfjRun.StepExecution::name).contains("LOAN_CLOSURE");

        // Reclamee, prelevee, encours nul : le credit sort du portefeuille le soir meme.
        assertThat(soldeDe(dossier.pret()).isZero()).isTrue();
        assertThat(statut(dossier.contrat())).isEqualTo("CLOSED");

        // Une cloture n'est pas plus definitive que l'arrete qui l'a prononcee.
        engine.cancel(run.id(), ACTOR, jour, "erreur de parametrage");
        assertThat(statut(dossier.contrat())).isEqualTo("ACTIVE");
        assertThat(creancesOuvertes(dossier.contrat())).isZero();
    }

    private static String statut(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT status FROM loan_contract WHERE id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture du statut", e);
            }
        });
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

    @Test
    @DisplayName("le TFJ classe le credit impaye, dote la provision et suspend les interets")
    void classificationEtProvision() {
        // Grille resserree : le seuil de declassement tombe des le premier jour de retard, ce qui
        // rend le cycle observable sur deux journees de TFJ.
        Account dotations = account("L4-DOT", AccountKind.GL, NormalBalance.DEBIT);
        Account provisions = account("L4-PROV", AccountKind.GL, NormalBalance.CREDIT);
        Account reserves = account("L4-RESERVES", AccountKind.GL, NormalBalance.CREDIT);
        profilDeRisque();

        Dossier dossier = dossier("L4", false, Map.of(
            LoanCatalog.P_RISK_PROFILE, "GRILLE-TFJ",
            LoanCatalog.P_PROVISION_EXPENSE, dotations.id().toString(),
            LoanCatalog.P_PROVISION_ALLOWANCE, provisions.id().toString(),
            LoanCatalog.P_RESERVED_INTEREST, reserves.id().toString()));

        // Premiere journee : l'echeance devient exigible, rien n'est preleve, le credit est encore
        // a jour au sens de la grille.
        assertThat(engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL).isCompleted()).isTrue();
        assertThat(soldeDe(provisions).isZero()).isTrue();

        // Lendemain : un jour de retard suffit a declasser.
        TfjRun second = engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
        assertThat(second.isCompleted()).as(second.summary()).isTrue();

        // Encours : 1 000 000 de capital, 10 000 d'interets echus et 39 d'interet de retard couru
        // le jour meme — l'etape de retard precede la classification, et son accrual entre donc
        // dans l'encours de la journee. 50 % de 1 010 039 = 505 019,5 -> 505 020.
        assertThat(soldeDe(provisions)).isEqualTo(xof("505020"));
        assertThat(soldeDe(dotations)).isEqualTo(xof("505020"));
        // Les interets deja constates sortent du resultat vers les interets reserves — chacun
        // repris sur le compte ou il avait ete constate : 10 000 d'echeance impayee, 39 de retard,
        // et les 307 de courus du premier jour de la deuxieme echeance (9 212 sur 30 jours), qui
        // etaient en produits depuis le matin.
        assertThat(soldeDe(reserves)).isEqualTo(xof("10346"));
        assertThat(soldeDe(dossier.produits()).isZero()).isTrue();

        engine.cancel(second.id(), ACTOR, businessDate(), "erreur de grille");

        // La contre-passation ramene la provision et les interets reserves a zero, et le
        // declassement passe en REVERSED : seule subsiste la classification saine de la veille, que
        // le traitement annule n'avait pas produite. Le TFJ suivant reclassera sans empiler une
        // seconde dotation sur la meme journee.
        assertThat(soldeDe(provisions).isZero()).isTrue();
        assertThat(soldeDe(reserves).isZero()).isTrue();
        assertThat(classificationsActives(dossier.contrat())).isEqualTo(1);
        assertThat(derniereClasse(dossier.contrat())).isEqualTo("SAIN");
    }

    // ------------------------------------------------------------------ outillage

    private record Dossier(UUID contrat, Account pret, Account courant, Account creances,
                           Account produits) {}

    /** Credit de 1 000 000 XOF a 12 % sur douze mois, premiere echeance a la journee traitee. */
    private static Dossier dossier(String code, boolean prelevementAutomatique) {
        return dossier(code, prelevementAutomatique, Map.of());
    }

    private static Dossier dossier(String code, boolean prelevementAutomatique,
                                   Map<String, String> surcharges) {
        return dossier(code, prelevementAutomatique, surcharges, 12);
    }

    private static Dossier dossier(String code, boolean prelevementAutomatique,
                                   Map<String, String> surcharges, int echeances) {
        LocalDate jour = businessDate();
        LocalDate deblocage = jour.minusDays(5);

        Account pret = account(code + "-PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT);
        Account courant = account(code + "-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Account creances = account(code + "-CREANCES", AccountKind.GL, NormalBalance.DEBIT);
        Account produits = account(code + "-PRODUITS", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account(code + "-TAXE", AccountKind.GL, NormalBalance.CREDIT);
        Account retard = account(code + "-RETARD", AccountKind.GL, NormalBalance.CREDIT);
        Account courus = account(code + "-ICNE", AccountKind.GL, NormalBalance.DEBIT);

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, creances.id().toString());
        parametres.put(LoanCatalog.P_ACCRUED_INTEREST, courus.id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(LoanCatalog.P_DIRECT_DEBIT, String.valueOf(prelevementAutomatique));
        parametres.put(LoanCatalog.P_LATE_RATE, "18");
        parametres.put(LoanCatalog.P_LATE_INCOME, retard.id().toString());
        parametres.putAll(surcharges);

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
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(echeances)
                .disbursedOn(deblocage).firstDueDate(jour).build()), ACTOR, APPROVER);

        return new Dossier(contrat, pret, courant, creances, produits);
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }

    /** Grille resserree, creee une seule fois pour l'entite partagee par les cas. */
    private static void profilDeRisque() {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM risk_profile WHERE legal_entity_id = ? AND code = ?")) {
                ps.setObject(1, ENTITY);
                ps.setString(2, "GRILLE-TFJ");
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return null;
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Recherche du profil", e);
            }
            UUID profil = io.corebanking.loan.service.RiskProfiles.createDraft(
                c, new io.corebanking.loan.service.RiskProfiles.Draft(
                    ENTITY, "Grille resserree", J1.minusMonths(1), null,
                    new io.corebanking.loan.RiskGrid("GRILLE-TFJ", List.of(
                        new io.corebanking.loan.RiskBucket(0, "SAIN", "Sain", 0, 0,
                                                           java.math.BigDecimal.ZERO, true),
                        new io.corebanking.loan.RiskBucket(1, "DOUTEUX", "Douteux", 1, null,
                                                           new java.math.BigDecimal("50"), false)),
                        io.corebanking.loan.Contagion.NONE, "DOUTEUX"),
                    ACTOR));
            io.corebanking.loan.service.RiskProfiles.activate(c, profil, APPROVER);
            return null;
        });
    }

    private static String derniereClasse(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT bucket_code FROM loan_classification"
                + " WHERE contract_id = ? AND status = 'ACTIVE'"
                + " ORDER BY classified_on DESC LIMIT 1")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de la classe", e);
            }
        });
    }

    private static int classificationsActives(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM loan_classification"
                + " WHERE contract_id = ? AND status = 'ACTIVE'")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des classifications", e);
            }
        });
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
