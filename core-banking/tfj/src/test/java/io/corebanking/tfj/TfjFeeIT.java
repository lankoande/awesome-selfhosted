package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les commissions dans la chaine complete du TFJ. */
class TfjFeeIT extends TfjTestBase {

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    @Test
    @DisplayName("la commission est percue avant les interets, qui portent donc sur le solde diminue")
    void commissionAvantInterets() {
        Account charges = account("CHG-800", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("CRS-800", AccountKind.GL, NormalBalance.CREDIT);
        Account produit = account("PRD-800", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("TAX-800", AccountKind.GL, NormalBalance.CREDIT);
        Account caisse = account("CSH-800", AccountKind.GL, NormalBalance.DEBIT);

        LocalDate jour = businessDate();
        Account client = compteAvecFrais("CLI-800", "EP-800", jour, charges, courus, produit, taxe,
                                         "100000", Map.of());
        deposit(client, caisse, "10000000", jour, "dep-800");

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.isCompleted()).as(run.summary()).isTrue();

        // 100 000 de commission + 18 000 de TOB preleves en date de valeur du jour.
        assertThat(soldeDe(produit)).isEqualTo(xof("100000"));
        assertThat(soldeDe(taxe)).isEqualTo(xof("18000"));
        assertThat(soldeDe(client)).isEqualTo(xof("9882000"));   // 10 000 000 - 118 000

        // Le point du test. Les interets du jour portent sur 9 882 000 et non sur 10 000 000 :
        // 9 882 000 x 6 % / 365 = 1 624,44 -> 1 624. Percevoir la commission apres le calcul des
        // interets donnerait 1 644, et l'ecart se reporterait sur toute la serie des jours
        // suivants puisque le cumul des interets courus est reconduit de jour en jour.
        assertThat(soldeDe(courus)).isEqualTo(xof("1624"));
    }

    @Test
    @DisplayName("l'annulation du TFJ rend la periode exigible : le TFJ suivant refacture la commission")
    void annulationRendLaPeriodeExigible() {
        Account charges = account("CHG-801", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("CRS-801", AccountKind.GL, NormalBalance.CREDIT);
        Account produit = account("PRD-801", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("TAX-801", AccountKind.GL, NormalBalance.CREDIT);
        Account caisse = account("CSH-801", AccountKind.GL, NormalBalance.DEBIT);

        LocalDate jour = businessDate();
        Account client = compteAvecFrais("CLI-801", "EP-801", jour, charges, courus, produit, taxe,
                                         "50000", Map.of());
        deposit(client, caisse, "1000000", jour, "dep-801");

        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(premier.isCompleted()).isTrue();
        assertThat(soldeDe(produit)).isEqualTo(xof("50000"));

        engine.cancel(premier.id(), ACTOR, jour, "erreur de parametrage");

        // Sans neutralisation du registre des commissions, la periode resterait marquee facturee :
        // les ecritures seraient contre-passees et la commission perdue, sans ecart comptable.
        assertThat(denouements(client.id())).containsExactly("CANCELLED");

        TfjRun second = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(second.isCompleted()).as(second.summary()).isTrue();
        assertThat(denouements(client.id())).containsExactlyInAnyOrder("CANCELLED", "COLLECTED");
        assertThat(soldeDe(produit)).isEqualTo(xof("50000"));    // percue une fois, pas deux
    }

    @Test
    @DisplayName("la reprise d'un TFJ ne refacture pas les commissions deja percues")
    void repriseNeRefacturePas() {
        Account charges = account("CHG-802", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("CRS-802", AccountKind.GL, NormalBalance.CREDIT);
        Account produit = account("PRD-802", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account("TAX-802", AccountKind.GL, NormalBalance.CREDIT);
        Account caisse = account("CSH-802", AccountKind.GL, NormalBalance.DEBIT);

        LocalDate jour = businessDate();
        Account client = compteAvecFrais("CLI-802", "EP-802", jour, charges, courus, produit, taxe,
                                         "30000", Map.of());
        deposit(client, caisse, "1000000", jour, "dep-802");

        engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        Money interetsApresPremier = soldeDe(courus);

        // Le TFJ du lendemain : la periode mensuelle n'est pas echue, rien ne doit etre refacture.
        engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);

        assertThat(denouements(client.id())).containsExactly("COLLECTED");
        assertThat(soldeDe(produit)).isEqualTo(xof("30000"));
        // Seuls les interets du second jour se sont ajoutes : la commission, elle, n'est pas due.
        assertThat(soldeDe(courus)).isGreaterThan(interetsApresPremier);
    }

    // ------------------------------------------------------------------ outillage

    /**
     * Compte d'epargne remunere a 6 % et porteur de frais de tenue mensuels a terme a echoir.
     *
     * <p>L'echeance est ancree sur la journee traitee : la periode s'ouvre le jour meme, et la
     * commission est donc exigible ce jour-la quelle que soit la longueur du mois. Ancrer sur une
     * date fixe rendrait le test dependant du calendrier.
     */
    private static Account compteAvecFrais(String code, String produitCode, LocalDate jour,
                                           Account charges, Account courus, Account produitFrais,
                                           Account taxe, String montantFrais,
                                           Map<String, String> surcharges) {
        Account client = account(code, AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(ProductCatalog.P_RATE, "6");
        parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
        parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString());
        parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.label", "Frais de tenue de compte");
        parametres.put("fee.TENUE.frequency", "MONTHLY");
        parametres.put("fee.TENUE.anchor", jour.toString());
        parametres.put("fee.TENUE.timing", "IN_ADVANCE");
        parametres.put("fee.TENUE.basis", "FLAT");
        parametres.put("fee.TENUE.amount", montantFrais);
        parametres.put("fee.TENUE.tax_rate", "18");
        parametres.put("fee.TENUE.income_account", produitFrais.id().toString());
        parametres.put("fee.TENUE.tax_account", taxe.id().toString());
        parametres.putAll(surcharges);

        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, produitCode, "SAVINGS_ACCOUNT", "Epargne", "XOF", jour.minusMonths(1), null,
                parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            ProductCatalog.assignProduct(c, client.id(), produitCode, jour.minusMonths(1), null);
            return null;
        });
        return client;
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }

    private static List<String> denouements(UUID accountId) {
        return database.inTransaction(c -> {
            List<String> outcomes = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT outcome FROM fee_charge WHERE account_id = ? ORDER BY generation")) {
                ps.setObject(1, accountId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        outcomes.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des denouements", e);
            }
            return outcomes;
        });
    }
}
