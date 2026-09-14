package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.PrepaymentMode;
import io.corebanking.loan.ScheduleGenerator;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanPrepaymentIT extends LoanTestBase {

    private static final LocalDate ANTICIPATION = LocalDate.of(2027, 3, 15);

    /** 1 000 000 XOF a 12 % sur vingt-quatre mensualites. */
    private static AmortisationSchedule echeancier() {
        return ScheduleGenerator.generate(
            LoanTerms.of(Money.of("1000000", Currencies.XOF)).ratePercent("12").instalments(24)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }

    private record Dossier(Decor decor, UUID contrat, Account indemnites) {}

    private static Dossier dossier(String code, Map<String, String> extra) {
        Decor decor = decor(code);
        Account indemnites = account(decor.entityId(), code + "-INDEMNITES", AccountKind.GL,
                                     NormalBalance.CREDIT);
        Map<String, String> parametres = new LinkedHashMap<>(extra);
        parametres.put(LoanCatalog.P_PREPAY_INDEMNITY_ACCOUNT, indemnites.id().toString());
        product(decor, "CRED-" + code, parametres);

        UUID contrat = contract(decor, "REF-" + code, "CRED-" + code, "1000000");
        loanService.disburse(contrat, echeancier(), ACTOR, APPROVER);
        return new Dossier(decor, contrat, indemnites);
    }

    /**
     * Six echeances rendues exigibles et reglees : le credit est a jour, capital restant du
     * 771 927. C'est l'etat dans lequel un remboursement anticipe se presente reellement.
     */
    private static Dossier servi(String code, Map<String, String> extra) {
        Dossier dossier = dossier(code, extra);
        loanService.makeDue(dossier.decor().entityId(), ANTICIPATION, ACTOR, UUID.randomUUID());
        loanService.settle(dossier.contrat(), xof("282438"), ANTICIPATION, "MANUAL",
                           IdempotencyKey.of("REG-" + code), ACTOR, null);
        return dossier;
    }

    // ------------------------------------------------------------------ effet comptable

    @Test
    @DisplayName("le remboursement anticipe reduit l'encours et produit une indemnite")
    void effetComptable() {
        Dossier dossier = dossier("P1", Map.of(LoanCatalog.P_PREPAY_RATE, "2"));

        var remboursement = loanService.prepay(dossier.contrat(), xof("300000"),
                                               PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
                                               IdempotencyKey.of("ANT-P1"), ACTOR, APPROVER);

        // 2 % de 300 000 : 6 000 d'indemnite, creditee sur un compte distinct des interets. Ce
        // n'est pas un interet — elle ne remunere aucune duree — et la confondre avec un produit
        // d'interets fausserait la marge d'interet du portefeuille.
        assertThat(remboursement.indemnity()).isEqualTo(xof("6000"));
        assertThat(remboursement.totalDue()).isEqualTo(xof("306000"));
        assertThat(soldeDe(dossier.indemnites())).isEqualTo(xof("6000"));
        assertThat(soldeDe(dossier.decor().pret())).isEqualTo(xof("700000"));
        assertThat(soldeDe(dossier.decor().courant())).isEqualTo(xof("694000"));
    }

    @Test
    @DisplayName("reduire la duree publie un plan plus court ; reduire l'echeance en publie un plus leger")
    void lesDeuxOptions() {
        Dossier duree = servi("P2", Map.of());
        Dossier echeance = servi("P3", Map.of());

        var reduiteEnDuree = loanService.prepay(duree.contrat(), xof("300000"),
                                                PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
                                                IdempotencyKey.of("ANT-P2"), ACTOR, APPROVER);
        var reduiteEnEcheance = loanService.prepay(echeance.contrat(), xof("300000"),
                                                   PrepaymentMode.REDUCE_INSTALMENT, ANTICIPATION,
                                                   IdempotencyKey.of("ANT-P3"), ACTOR, APPROVER);

        // Le choix appartient a l'emprunteur : reduire la duree economise bien plus d'interets.
        assertThat(reduiteEnDuree.newSchedule().instalments()).hasSize(11);
        assertThat(reduiteEnEcheance.newSchedule().instalments()).hasSize(18);
        assertThat(reduiteEnDuree.newSchedule().totalInterest())
            .isLessThan(reduiteEnEcheance.newSchedule().totalInterest());
    }

    @Test
    @DisplayName("le nouveau plan est publie en version, l'ancien conserve et clos")
    void versionnement() {
        Dossier dossier = dossier("P4", Map.of());

        loanService.prepay(dossier.contrat(), xof("300000"), PrepaymentMode.SHORTEN_TERM,
                           ANTICIPATION, IdempotencyKey.of("ANT-P4"), ACTOR, APPROVER);

        assertThat(versions(dossier.contrat())).isEqualTo(2);
        assertThat(motif(dossier.contrat(), 2)).isEqualTo("EARLY_REPAYMENT");
        // L'echeancier contractuel initial reste consultable : c'est une piece du dossier en cas
        // de contentieux sur ce que le client devait avant son remboursement.
        assertThat(motif(dossier.contrat(), 1)).isEqualTo("INITIAL");
    }

    @Test
    @DisplayName("un remboursement qui solde le capital clot le contrat")
    void remboursementTotal() {
        Dossier dossier = dossier("P5", Map.of());

        var remboursement = loanService.prepay(dossier.contrat(), xof("1000000"),
                                               PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
                                               IdempotencyKey.of("ANT-P5"), ACTOR, APPROVER);

        assertThat(remboursement.settlesLoan()).isTrue();
        assertThat(soldeDe(dossier.decor().pret()).isZero()).isTrue();
        assertThat(statut(dossier.contrat())).isEqualTo("CLOSED");
        assertThat(versions(dossier.contrat())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("un remboursement anticipe est refuse tant que des echeances restent dues")
    void impayesAvantAnticipation() {
        Dossier dossier = dossier("P6", Map.of());
        loanService.makeDue(dossier.decor().entityId(), PREMIERE_ECHEANCE, ACTOR,
                            UUID.randomUUID());

        // Laisser rembourser du capital non echu alors que des echeances restent dues ferait
        // courir des penalites sur un client qui vient de verser plusieurs mois d'avance.
        assertThatThrownBy(() -> loanService.prepay(
            dossier.contrat(), xof("300000"), PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
            IdempotencyKey.of("ANT-P6"), ACTOR, APPROVER))
            .isInstanceOf(LoanService.ArrearsOutstandingException.class)
            .hasMessageContaining("solder les impayes d'abord");
    }

    @Test
    @DisplayName("un remboursement superieur au capital restant est refuse")
    void remboursementExcessif() {
        Dossier dossier = dossier("P7", Map.of());

        assertThatThrownBy(() -> loanService.prepay(
            dossier.contrat(), xof("1500000"), PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
            IdempotencyKey.of("ANT-P7"), ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ne le rend pas crediteur");
    }

    @Test
    @DisplayName("l'indemnite est plafonnee par la loi, et c'est le plafond le plus bas qui s'applique")
    void indemnitePlafonnee() {
        Dossier dossier = dossier("P8", Map.of(
            LoanCatalog.P_PREPAY_RATE, "3",
            LoanCatalog.P_PREPAY_CAP_PCT, "2",
            LoanCatalog.P_PREPAY_CAP_MONTHS, "6"));

        var remboursement = loanService.prepay(dossier.contrat(), xof("300000"),
                                               PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
                                               IdempotencyKey.of("ANT-P8"), ACTOR, APPROVER);

        // 3 % contractuels feraient 9 000. Le plafond en pourcentage ramene a 6 000, celui en mois
        // d'interets — six mois a 12 % — a 18 000. Les deux s'appliquent, le plus bas l'emporte.
        assertThat(remboursement.indemnity()).isEqualTo(xof("6000"));
    }

    @Test
    @DisplayName("un credit sans indemnite parametree n'en produit aucune")
    void sansIndemnite() {
        Dossier dossier = dossier("P9", Map.of());

        var remboursement = loanService.prepay(dossier.contrat(), xof("300000"),
                                               PrepaymentMode.SHORTEN_TERM, ANTICIPATION,
                                               IdempotencyKey.of("ANT-P9"), ACTOR, APPROVER);

        assertThat(remboursement.indemnity().isZero()).isTrue();
        assertThat(soldeDe(dossier.indemnites()).isZero()).isTrue();
    }

    @Test
    @DisplayName("le TFJ suivant lit le nouveau plan")
    void tfjSuivantLitLeNouveauPlan() {
        Dossier dossier = servi("P10", Map.of());
        loanService.prepay(dossier.contrat(), xof("300000"), PrepaymentMode.REDUCE_INSTALMENT,
                           ANTICIPATION, IdempotencyKey.of("ANT-P10"), ACTOR, APPROVER);

        loanService.makeDue(dossier.decor().entityId(), LocalDate.of(2027, 4, 15), ACTOR,
                            UUID.randomUUID());

        // L'echeance du nouveau plan, et non celle de l'ancien : 28 779 au lieu de 47 073.
        Money du = creances(dossier.contrat()).stream()
            .filter(creance -> creance.dueDate().equals(LocalDate.of(2027, 4, 15)))
            .map(Creance::original).reduce(Money.zero(Currencies.XOF), Money::plus);
        assertThat(du).isEqualTo(xof("28779"));
    }

    // ------------------------------------------------------------------ outillage

    private static int versions(UUID contractId) {
        return lireEntier("SELECT count(*) FROM loan_schedule WHERE contract_id = ?", contractId);
    }

    private static String motif(UUID contractId, int version) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT reason FROM loan_schedule WHERE contract_id = ? AND version = ?")) {
                ps.setObject(1, contractId);
                ps.setInt(2, version);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture du motif", e);
            }
        });
    }

    private static String statut(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT status FROM loan_contract WHERE id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture du statut", e);
            }
        });
    }

    private static int lireEntier(String sql, UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture", e);
            }
        });
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
