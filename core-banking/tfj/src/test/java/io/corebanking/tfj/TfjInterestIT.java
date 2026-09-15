package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.service.InterestReconciliation;
import io.corebanking.interest.service.WithholdingTaxes;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.product.ProductCatalog;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les interets dans la chaine complete du TFJ : calcules chaque nuit, regles en fin de periode. */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class TfjInterestIT extends TfjTestBase {

    private static final LocalDate FIN_TRIMESTRE = LocalDate.of(2026, 9, 30);

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private static Money soldeDe(Account account) {
        return database.inTransaction(c -> Balances.current(c, account.id()));
    }

    private static void post(String key, Account debit, Account credit, String amount,
                             LocalDate date) {
        postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, date, "MANUAL", ACTOR,
            List.of(PostingLine.debit(debit.id(), xof(amount), date, null),
                    PostingLine.credit(credit.id(), xof(amount), date, null))));
    }

    /** Enchaine les TFJ jusqu'a ce que la journee donnee soit arretee. */
    private static void arreterJusquAu(LocalDate inclus) {
        while (!businessDate().isAfter(inclus)) {
            TfjRun run = engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
            assertThat(run.isCompleted()).as(run.summary()).isTrue();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("a la fin du trimestre, le TFJ capitalise les interets nets de retenue et arrete les agios taxe comprise")
    void quarter_end_settles_both_sides() {
        LocalDate depart = businessDate();                            // 14 septembre
        assertThat(depart).isEqualTo(J1);

        // --- Epargne remuneree a 6 %, capitalisee chaque trimestre, retenue IRC de 15 %.
        Account caisse = account("CAISSE-INT", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-INT", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-INT", AccountKind.GL, NormalBalance.CREDIT);
        Account irc = account("IRC-A-REVERSER", AccountKind.GL, NormalBalance.CREDIT);
        database.inTransaction(c -> WithholdingTaxes.declare(
            c, ENTITY, "IRC", new BigDecimal("15"), irc.id(), LocalDate.of(2026, 1, 1), null,
            ACTOR));
        Account epargne = account("CLI-EPARGNE", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        produit("EP-IRC", "SAVINGS_ACCOUNT", epargne, Map.of(
            ProductCatalog.P_RATE, "6",
            ProductCatalog.P_DAY_COUNT, "ACT_365",
            ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name(),
            ProductCatalog.P_CAPITALISATION, "QUARTERLY",
            ProductCatalog.P_WITHHOLDING, "IRC",
            ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString(),
            ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString()));
        deposit(epargne, caisse, "1000000", depart, "dep-epargne");

        // --- Compte courant a decouvert : 300 000 autorises a 12 %, 18 % au-dela, 10 % de taxe.
        Account agiosCourus = account("AGIOS-COURUS", AccountKind.GL, NormalBalance.DEBIT);
        Account produitsAgios = account("PRODUITS-AGIOS", AccountKind.GL, NormalBalance.CREDIT);
        Account taf = account("TAF-COLLECTEE", AccountKind.GL, NormalBalance.CREDIT);
        Account courant = account("CLI-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        produit("CC-AGIOS", "CURRENT_ACCOUNT", courant, Map.ofEntries(
            Map.entry(ProductCatalog.P_RATE, "0"),
            Map.entry(ProductCatalog.P_DAY_COUNT, "ACT_365"),
            Map.entry(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name()),
            Map.entry(ProductCatalog.P_CAPITALISATION, "MONTHLY"),
            Map.entry(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString()),
            Map.entry(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString()),
            Map.entry(ProductCatalog.P_OD_RATE, "12"),
            Map.entry(ProductCatalog.P_OD_EXCESS_RATE, "18"),
            Map.entry(ProductCatalog.P_OD_SETTLEMENT, "MONTHLY"),
            Map.entry(ProductCatalog.P_OD_TAX_RATE, "10"),
            Map.entry(ProductCatalog.P_OD_TAX_ACCOUNT, taf.id().toString()),
            Map.entry(ProductCatalog.P_OD_DEBIT_ACCOUNT, agiosCourus.id().toString()),
            Map.entry(ProductCatalog.P_OD_CREDIT_ACCOUNT, produitsAgios.id().toString())));
        autoriser(courant, "300000", depart);
        post("retrait-courant", courant, caisse, "500000", depart);

        arreterJusquAu(FIN_TRIMESTRE);
        assertThat(businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));

        // Dix-sept journees, du 14 au 30 septembre.
        // Epargne : 1 000 000 x 6 % x 17 / 365 = 2 794,52 -> 2 795 ; retenue 419,25 -> 419.
        assertThat(soldeDe(epargne)).isEqualTo(xof("1002376"));
        assertThat(soldeDe(irc)).isEqualTo(xof("419"));
        assertThat(soldeDe(charges)).isEqualTo(xof("2795"));
        assertThat(soldeDe(courus).isZero()).isTrue();
        // Agios : 300 000 x 12 % et 200 000 x 18 %, soit 72 000 l'an, 3 353,42 -> 3 353 ; taxe 335.
        assertThat(soldeDe(produitsAgios)).isEqualTo(xof("3353"));
        assertThat(soldeDe(taf)).isEqualTo(xof("335"));
        assertThat(soldeDe(agiosCourus).isZero()).isTrue();
        assertThat(soldeDe(courant)).isEqualTo(xof("-503688"));
        assertThat(reglements(epargne, "CREDITOR")).isEqualTo(1);
        assertThat(reglements(courant, "DEBTOR")).isEqualTo(1);
        // Le cote crediteur du compte courant, a taux nul, a ete regle pour zero : la periode est
        // marquee reglee sans ecriture, et ne sera pas reexaminee chaque nuit.
        assertThat(reglements(courant, "CREDITOR")).isEqualTo(1);

        // Le lendemain, les interets courent sur le capital augmente : 1 002 376 a 6 % font
        // 164,77. Le cumul exact passe de 2 794,52 a 2 959,29, arrondi 2 959, dont 2 795 deja
        // imputes : 164 — le demi-franc impute en trop le 30 est repris, jamais accumule.
        TfjRun octobre = engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
        assertThat(octobre.isCompleted()).as(octobre.summary()).isTrue();
        assertThat(soldeDe(courus)).isEqualTo(xof("164"));
        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            assertThat(new InterestReconciliation().run(c, ENTITY, businessDate(), null))
                .isEmpty();
            return null;
        });
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("un ecart entre le sous-livre des interets et le grand livre arrete la journee, et la nomme")
    void a_sub_ledger_gap_blocks_the_day() {
        LocalDate jour = businessDate();
        Account caisse = account("CAISSE-ECART", AccountKind.GL, NormalBalance.DEBIT);
        Account charges = account("CHARGES-ECART", AccountKind.GL, NormalBalance.DEBIT);
        Account courus = account("COURUS-ECART", AccountKind.GL, NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-ECART", charges, courus, "EP-ECART");
        deposit(client, caisse, "10000000", jour, "dep-ecart");
        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(premier.isCompleted()).as(premier.summary()).isTrue();

        // Une ecriture manuelle sur le compte de courus : la balance reste equilibree.
        LocalDate lendemain = businessDate();
        post("manuel-courus", caisse, courus, "100", lendemain);

        TfjRun bloque = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);
        assertThat(bloque.status()).isEqualTo(TfjRun.Status.FAILED);
        assertThat(bloque.failedStep()).isPresent();
        assertThat(bloque.failedStep().get().name()).isEqualTo("RECONCILIATION");
        assertThat(String.join(" ", bloque.failedStep().get().anomalies()))
            .contains(InterestReconciliation.CHECK)
            .contains(courus.id().toString());
        assertThat(businessDate()).isEqualTo(lendemain);          // la journee n'a pas bascule

        // L'ecart repris, la journee se reprend a l'etape fautive et bascule.
        post("manuel-courus-retour", courus, caisse, "100", lendemain);
        TfjRun repris = engine.resume(bloque.id(), ACTOR);
        assertThat(repris.isCompleted()).as(repris.summary()).isTrue();
        assertThat(businessDate()).isAfter(lendemain);
    }

    // ------------------------------------------------------------------ outillage

    private static void produit(String code, String famille, Account client,
                                Map<String, String> parametres) {
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, code, famille, code, "XOF", J1.minusMonths(1), null,
                new LinkedHashMap<>(parametres), List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            ProductCatalog.assignProduct(c, client.id(), code, J1.minusMonths(1), null);
            return null;
        });
    }

    private static void autoriser(Account account, String montant, LocalDate depuis) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO overdraft_limit(account_id, amount, valid_from) VALUES (?,?,?)")) {
                ps.setObject(1, account.id());
                ps.setBigDecimal(2, new BigDecimal(montant));
                ps.setObject(3, depuis);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Autorisation de decouvert", e);
            }
            return null;
        });
    }

    private static int reglements(Account account, String side) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM interest_settlement WHERE account_id = ? AND side = ?"
                + " AND status = 'ACTIVE'")) {
                ps.setObject(1, account.id());
                ps.setString(2, side);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des reglements", e);
            }
        });
    }
}
