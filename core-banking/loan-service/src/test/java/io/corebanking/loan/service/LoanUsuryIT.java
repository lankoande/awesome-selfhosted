package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanUsuryIT extends LoanTestBase {

    private static AmortisationSchedule echeancier() {
        return ScheduleGenerator.generate(
            LoanTerms.of(Money.of("1000000", Currencies.XOF)).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }

    @Test
    @DisplayName("les frais de dossier sont retenus au deblocage : le client recoit le net")
    void fraisRetenusAuDeblocage() {
        Decor decor = decor("U1");
        product(decor, "CRED-U1", Map.of(
            LoanCatalog.P_FEE_INCOME, decor.produitsInterets().id().toString()));
        UUID contrat = contract(decor, "REF-U1", "CRED-U1", "1000000");

        loanService.disburse(contrat, echeancier(), xof("20000"), ACTOR, APPROVER);

        // L'encours nait pour le capital entier, l'emprunteur recoit le net, et la difference est
        // un produit acquis d'entree. C'est ce qui creuse l'ecart entre taux nominal et taux
        // effectif : le client rembourse 1 000 000 alors qu'il en a recu 980 000.
        assertThat(soldeDe(decor.pret())).isEqualTo(xof("1000000"));
        assertThat(soldeDe(decor.courant())).isEqualTo(xof("980000"));
        assertThat(soldeDe(decor.produitsInterets())).isEqualTo(xof("20000"));
    }

    @Test
    @DisplayName("le taux effectif est arrete au deblocage et conserve avec sa convention")
    void tegConserve() {
        Decor decor = decor("U2");
        product(decor, "CRED-U2", Map.of(
            LoanCatalog.P_FEE_INCOME, decor.produitsInterets().id().toString(),
            LoanCatalog.P_TEG_METHOD, "ACTUARIAL"));
        UUID contrat = contract(decor, "REF-U2", "CRED-U2", "1000000");

        loanService.disburse(contrat, echeancier(), xof("20000"), ACTOR, APPROVER);

        // Le chiffre n'a aucun sens sans sa convention : le meme echeancier affiche 15,89 % en
        // proportionnel et 17,10 % en actuariel. Les deux sont conserves ensemble.
        assertThat(teg(contrat)).isEqualByComparingTo("17.101880");
        assertThat(methode(contrat)).isEqualTo("ACTUARIAL");
    }

    @Test
    @DisplayName("un credit dont le cout depasse le plafond d'usure n'est pas debloque")
    void plafondDepasse() {
        Decor decor = decor("U3");
        product(decor, "CRED-U3", Map.of(
            LoanCatalog.P_FEE_INCOME, decor.produitsInterets().id().toString(),
            LoanCatalog.P_USURY_RATE, "15"));
        UUID contrat = contract(decor, "REF-U3", "CRED-U3", "1000000");

        assertThatThrownBy(() ->
            loanService.disburse(contrat, echeancier(), xof("20000"), ACTOR, APPROVER))
            .isInstanceOf(LoanService.UsuryCeilingExceededException.class)
            .hasMessageContaining("au-dela du plafond d'usure");

        // Rien n'a ete verse, rien n'a ete publie : le refus intervient avant tout effet.
        assertThat(soldeDe(decor.courant()).isZero()).isTrue();
        assertThat(soldeDe(decor.pret()).isZero()).isTrue();
        assertThat(echeanciers(contrat)).isZero();
    }

    @Test
    @DisplayName("le meme credit sans frais de dossier passe sous le plafond")
    void plafondRespecteSansFrais() {
        Decor decor = decor("U4");
        product(decor, "CRED-U4", Map.of(
            LoanCatalog.P_FEE_INCOME, decor.produitsInterets().id().toString(),
            LoanCatalog.P_USURY_RATE, "15"));
        UUID contrat = contract(decor, "REF-U4", "CRED-U4", "1000000");

        loanService.disburse(contrat, echeancier(), null, ACTOR, APPROVER);

        // Taux nominal identique, echeancier identique : seuls les frais faisaient franchir le
        // plafond. Un controle porte sur le taux nominal n'aurait rien vu dans les deux cas.
        assertThat(teg(contrat)).isEqualByComparingTo("12.028136");
        assertThat(soldeDe(decor.courant())).isEqualTo(xof("1000000"));
    }

    @Test
    @DisplayName("un produit sans plafond ne plafonne rien")
    void sansPlafond() {
        Decor decor = decor("U5");
        product(decor, "CRED-U5", Map.of(
            LoanCatalog.P_FEE_INCOME, decor.produitsInterets().id().toString()));
        UUID contrat = contract(decor, "REF-U5", "CRED-U5", "1000000");

        // Tous les pays n'imposent pas de plafond d'usure : l'absence du parametre est un choix,
        // pas un oubli, et le taux est tout de meme calcule et conserve.
        loanService.disburse(contrat, echeancier(), xof("200000"), ACTOR, APPROVER);

        assertThat(teg(contrat)).isGreaterThan(new BigDecimal("50"));
    }

    // ------------------------------------------------------------------ outillage

    private static BigDecimal teg(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT teg_percent FROM loan_contract WHERE id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBigDecimal(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture du taux effectif", e);
            }
        });
    }

    private static String methode(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement("SELECT teg_method FROM loan_contract WHERE id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de la convention", e);
            }
        });
    }

    private static int echeanciers(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM loan_schedule WHERE contract_id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des echeanciers", e);
            }
        });
    }

    private static Money soldeDe(io.corebanking.ledger.domain.account.Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
