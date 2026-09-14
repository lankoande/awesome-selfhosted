package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanLateChargesIT extends LoanTestBase {

    private static LoanLateChargesService lateService;

    private static LoanLateChargesService service() {
        if (lateService == null) {
            lateService = new LoanLateChargesService(database, postingService);
        }
        return lateService;
    }

    private static AmortisationSchedule echeancier() {
        return ScheduleGenerator.generate(
            LoanTerms.of(Money.of("1000000", Currencies.XOF)).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }

    /** Dossier avec comptes de produits de retard dedies. */
    private record Retard(Decor decor, UUID contrat, Account produitsRetard, Account penalites) {}

    private static Retard dossier(String code, Map<String, String> regime) {
        Decor decor = decor(code);
        Account produitsRetard = account(decor.entityId(), code + "-RETARD", AccountKind.GL,
                                         NormalBalance.CREDIT);
        Account penalites = account(decor.entityId(), code + "-PENALITES", AccountKind.GL,
                                    NormalBalance.CREDIT);
        Map<String, String> parametres = new LinkedHashMap<>(regime);
        parametres.put(LoanCatalog.P_LATE_INCOME, produitsRetard.id().toString());
        parametres.put(LoanCatalog.P_PENALTY_INCOME, penalites.id().toString());
        product(decor, "CRED-" + code, parametres);

        UUID contrat = contract(decor, "REF-" + code, "CRED-" + code, "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        return new Retard(decor, contrat, produitsRetard, penalites);
    }

    // ------------------------------------------------------------------ interets de retard

    @Test
    @DisplayName("l'interet de retard court sur le capital impaye, franchise deduite")
    void interetDeRetard() {
        Retard dossier = dossier("W1", Map.of(
            LoanCatalog.P_GRACE_DAYS, "5",
            LoanCatalog.P_LATE_RATE, "18"));

        // Franchise jusqu'au 20 inclus : la premiere journee facturee est le 21.
        var bilan = service().charge(dossier.decor().entityId(), LocalDate.of(2026, 10, 25),
                                     ACTOR, UUID.randomUUID());

        assertThat(bilan.anomalies()).isEmpty();
        // 78 849 de capital impaye a 18 % sur cinq jours (21 au 25) en ACT/365 :
        // 78 849 x 0,18 x 5 / 365 = 194,37 -> 194.
        assertThat(bilan.lateInterest()).isEqualTo(xof("194"));
        assertThat(soldeDe(dossier.produitsRetard())).isEqualTo(xof("194"));
    }

    @Test
    @DisplayName("l'assiette exclut les interets de retard eux-memes : aucune capitalisation")
    void aucuneCapitalisation() {
        Retard dossier = dossier("W2", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        UUID entite = dossier.decor().entityId();

        service().charge(entite, LocalDate.of(2026, 11, 15), ACTOR, UUID.randomUUID());
        Money apresUnMois = soldeDe(dossier.produitsRetard());
        service().charge(entite, LocalDate.of(2026, 11, 16), ACTOR, UUID.randomUUID());
        Money apresUnJour = soldeDe(dossier.produitsRetard()).minus(apresUnMois);

        // La journee du 16 porte sur le meme capital impaye que les precedentes : 78 849, et non
        // sur ce capital augmente du mois d'interets de retard deja couru. L'ecart serait
        // invisible sur un jour et considerable sur un contentieux de deux ans.
        assertThat(apresUnJour).isEqualTo(xof("39"));   // 78 849 x 0,18 / 365 = 38,88
    }

    @Test
    @DisplayName("le cumul s'arrondit, pas la journee : aucune derive sur un an de retard")
    void aucuneDerive() {
        Retard dossier = dossier("W3", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        UUID entite = dossier.decor().entityId();

        // Une passe par jour pendant soixante jours, comme le ferait un TFJ quotidien.
        for (LocalDate jour = LocalDate.of(2026, 10, 16); !jour.isAfter(LocalDate.of(2026, 12, 14));
             jour = jour.plusDays(1)) {
            service().charge(entite, jour, ACTOR, UUID.randomUUID());
        }
        Money parJournees = soldeDe(dossier.produitsRetard());

        // Le meme retard calcule en une seule passe doit donner exactement le meme montant.
        Retard temoin = dossier("W3B", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        service().charge(temoin.decor().entityId(), LocalDate.of(2026, 12, 14), ACTOR,
                         UUID.randomUUID());

        assertThat(parJournees).isEqualTo(soldeDe(temoin.produitsRetard()));
        // Soixante journees a 38,884 XOF : 2 333,07 de cumul exact, impute a 2 333. L'arrondi
        // quotidien aurait donne 60 x 39 = 2 340, soit sept francs de trop par contrat et par
        // deux mois de retard — et l'ecart croit lineairement avec la duree du contentieux.
        assertThat(parJournees).isEqualTo(xof("2333"));
    }

    @Test
    @DisplayName("un reglement intervenu en cours de rattrapage reduit l'assiette des jours suivants")
    void assietteReconstituee() {
        Retard dossier = dossier("W4", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        UUID entite = dossier.decor().entityId();

        // Le client regle le 31 octobre, mais le traitement n'est relance que le 15 novembre.
        loanService.settle(dossier.contrat(), xof("50000"), LocalDate.of(2026, 10, 31), "MANUAL",
                           IdempotencyKey.of("REG-W4"), ACTOR, null);
        var bilan = service().charge(entite, LocalDate.of(2026, 11, 15), ACTOR, UUID.randomUUID());

        // Calculer sur l'assiette d'aujourd'hui pour toutes les journees facturerait le client
        // d'une dette qu'il avait deja reglee — ou, dans l'autre sens, lui offrirait quinze jours.
        // Du 16 au 30 octobre : 78 849 impayes, quinze journees a 38,884. A compter du 31, date de
        // valeur du reglement : 38 849, l'imputation ayant solde les interets puis entame le
        // capital, seize journees a 19,158. Cumul 889,8 -> 890.
        assertThat(bilan.lateInterest()).isEqualTo(xof("890"));
    }

    @Test
    @DisplayName("un credit a jour ne produit aucune charge de retard")
    void creditAJour() {
        Decor decor = decor("W5");
        Account produitsRetard = account(decor.entityId(), "W5-RETARD", AccountKind.GL,
                                         NormalBalance.CREDIT);
        product(decor, "CRED-W5", Map.of(
            LoanCatalog.P_DIRECT_DEBIT, "true",
            LoanCatalog.P_LATE_RATE, "18",
            LoanCatalog.P_LATE_INCOME, produitsRetard.id().toString()));
        UUID contrat = contract(decor, "REF-W5", "CRED-W5", "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        var bilan = service().charge(decor.entityId(), LocalDate.of(2026, 11, 1), ACTOR,
                                     UUID.randomUUID());

        assertThat(bilan.contractsCharged()).isZero();
        assertThat(soldeDe(produitsRetard).isZero()).isTrue();
    }

    // ------------------------------------------------------------------ penalites

    @Test
    @DisplayName("la penalite se percoit une fois par echeance, pas chaque jour")
    void penaliteUneSeuleFois() {
        Retard dossier = dossier("W6", Map.of(
            LoanCatalog.P_GRACE_DAYS, "5",
            LoanCatalog.P_PENALTY_MODE, "FLAT_PER_INSTALMENT",
            LoanCatalog.P_PENALTY_AMOUNT, "5000"));
        UUID entite = dossier.decor().entityId();

        service().charge(entite, LocalDate.of(2026, 10, 25), ACTOR, UUID.randomUUID());
        service().charge(entite, LocalDate.of(2026, 10, 26), ACTOR, UUID.randomUUID());
        var troisieme = service().charge(entite, LocalDate.of(2026, 10, 27), ACTOR,
                                         UUID.randomUUID());

        assertThat(troisieme.penalties()).isZero();
        assertThat(soldeDe(dossier.penalites())).isEqualTo(xof("5000"));
    }

    @Test
    @DisplayName("la penalite proportionnelle est encadree par son plancher et son plafond")
    void penaliteEncadree() {
        Retard dossier = dossier("W7", Map.of(
            LoanCatalog.P_PENALTY_MODE, "PERCENT_OF_OVERDUE",
            LoanCatalog.P_PENALTY_RATE, "10",
            LoanCatalog.P_PENALTY_CAP, "5000"));

        service().charge(dossier.decor().entityId(), LocalDate.of(2026, 10, 20), ACTOR,
                         UUID.randomUUID());

        // 10 % de 88 849 feraient 8 885 : le plafond ramene a 5 000.
        assertThat(soldeDe(dossier.penalites())).isEqualTo(xof("5000"));
    }

    @Test
    @DisplayName("chaque echeance impayee porte sa propre penalite")
    void unePenaliteParEcheance() {
        Retard dossier = dossier("W8", Map.of(
            LoanCatalog.P_PENALTY_MODE, "FLAT_PER_INSTALMENT",
            LoanCatalog.P_PENALTY_AMOUNT, "5000"));
        UUID entite = dossier.decor().entityId();

        loanService.makeDue(entite, LocalDate.of(2026, 11, 15), ACTOR, UUID.randomUUID());
        var bilan = service().charge(entite, LocalDate.of(2026, 11, 20), ACTOR, UUID.randomUUID());

        assertThat(bilan.penalties()).isEqualTo(2);
        assertThat(soldeDe(dossier.penalites())).isEqualTo(xof("10000"));
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("un cumul de taux au-dela du maximum admis est refuse")
    void cumulDeTauxRefuse() {
        io.corebanking.loan.LatePolicy regime = new io.corebanking.loan.LatePolicy(
            Currencies.XOF, new java.math.BigDecimal("18"),
            io.corebanking.loan.LateInterestBasis.OVERDUE_PRINCIPAL,
            io.corebanking.interest.daycount.DayCountConvention.ACT_365, 0,
            io.corebanking.loan.PenaltyMode.NONE, null, java.math.BigDecimal.ZERO, null, null,
            new java.math.BigDecimal("24"));

        assertThatThrownBy(() -> regime.requireCompatibleWith(new java.math.BigDecimal("12")))
            .isInstanceOf(io.corebanking.loan.LatePolicy.InvalidLatePolicyException.class)
            .hasMessageContaining("au-dela du maximum admis");
    }

    @Test
    @DisplayName("l'annulation du traitement reprend exactement l'interet de retard qu'il avait impute")
    void annulationReprendLInteret() {
        Retard dossier = dossier("W9", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        UUID entite = dossier.decor().entityId();
        UUID premier = UUID.randomUUID();
        service().charge(entite, LocalDate.of(2026, 11, 15), ACTOR, premier);
        Money avant = creanceDeRetard(dossier.contrat());
        assertThat(avant.isPositive()).isTrue();

        UUID second = UUID.randomUUID();
        service().charge(entite, LocalDate.of(2026, 11, 16), ACTOR, second);
        assertThat(creanceDeRetard(dossier.contrat())).isGreaterThan(avant);

        database.inTransaction(c -> {
            LoanStore.reverseLateInterest(c, second);
            LoanStore.cancelLateAccruals(c, second);
            return null;
        });

        // La creance revient exactement a ce qu'elle etait. Sans cette reprise, l'annulation
        // contre-passerait l'ecriture et laisserait la creance gonflee d'un montant dont plus
        // aucune ecriture ne rend compte.
        assertThat(creanceDeRetard(dossier.contrat())).isEqualTo(avant);
    }

    @Test
    @DisplayName("un accrual ne peut pas faire redevenir due une part deja reglee")
    void accrualNeReouvrePasUnReglement() {
        Retard dossier = dossier("W10", Map.of(LoanCatalog.P_LATE_RATE, "18"));
        service().charge(dossier.decor().entityId(), LocalDate.of(2026, 11, 15), ACTOR,
                         UUID.randomUUID());

        // Montant du en hausse, solde en hausse d'autant plus : la difference serait une part
        // reglee qui redeviendrait exigible, et le client paierait deux fois.
        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_receivable SET original_amount = original_amount + 10,"
                + " outstanding = outstanding + 50"
                + " WHERE contract_id = ? AND category = 'LATE_INTEREST'")) {
                ps.setObject(1, dossier.contrat());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("redeviendrait due");
    }

    @Test
    @DisplayName("le montant d'une creance ordinaire ne court jamais")
    void creanceOrdinaireFigee() {
        Retard dossier = dossier("W11", Map.of(LoanCatalog.P_LATE_RATE, "18"));

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_receivable SET original_amount = original_amount + 10,"
                + " outstanding = outstanding + 10"
                + " WHERE contract_id = ? AND category = 'PRINCIPAL'")) {
                ps.setObject(1, dossier.contrat());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("il ne court pas");
    }

    private static Money creanceDeRetard(UUID contractId) {
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
                throw new LedgerStoreException("Lecture de la creance de retard", e);
            }
        });
    }

    protected static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
