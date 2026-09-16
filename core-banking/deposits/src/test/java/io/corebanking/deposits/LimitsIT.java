package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les plafonds : du produit, du compte a deux, lus dans le journal, exacts apres annulation. */
class LimitsIT extends DepositsTestBase {

    @Test
    @DisplayName("le produit plafonne par operation, par jour et par mois ; le compte porte le sien a deux, et il l'emporte")
    void product_and_account_limits() {
        Decor decor = decor("LIM");
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_TRANSACTION_MAX, "100000");
        parametres.put(DepositCatalog.P_DAILY_DEBIT_MAX, "150000");
        parametres.put(DepositCatalog.P_MONTHLY_DEBIT_MAX, "380000");
        produit(decor, "EP-LIM", "SAVINGS_ACCOUNT", parametres);
        UUID compte = ouvrir(decor, "CLI-LIM", "EP-LIM", client(decor.entityId(), "T-LIM"));
        UUID autre = ouvrir(decor, "CLI-LIM-2", "EP-LIM", client(decor.entityId(), "T-LIM2"));
        verser(decor, compte, "1000000", "lim-0");

        // Par operation : 100 000 au plus.
        assertThatThrownBy(() -> retirer(decor, compte, "100001", "lim-1"))
            .isInstanceOf(Limits.LimitExceededException.class)
            .hasMessageContaining("par operation");
        retirer(decor, compte, "100000", "lim-2");                 // debite 100 590, frais compris

        // Par jour : 150 000, frais compris ; les 100 590 du retrait comptent deja.
        assertThatThrownBy(() -> virer(decor, compte, autre, "49500", "lim-3"))
            .isInstanceOf(Limits.LimitExceededException.class)
            .hasMessageContaining("journalier").hasMessageContaining("100590");
        virer(decor, compte, autre, "40000", "lim-4");             // 40 236 de plus : 140 826

        // Un retrait contre-passe ne compte plus.
        OperationsService.Receipt annule = retirer(decor, compte, "9000", "lim-5");
        assertThatThrownBy(() -> retirer(decor, compte, "1000", "lim-6"))
            .isInstanceOf(Limits.LimitExceededException.class);
        postingService.reverse(annule.entryId(), annule.bookingDate(), J,
                               io.corebanking.kernel.id.IdempotencyKey.of("lim-5-rev"), "erreur");
        retirer(decor, compte, "1000", "lim-7");

        // Le lendemain, le jour repart ; le mois cumule : 380 000 au plus, 343 596 deja debites.
        dater(decor, J.plusDays(1));
        retirer(decor, compte, "100000", "lim-8");
        dater(decor, J.plusDays(2));
        retirer(decor, compte, "100000", "lim-9");
        assertThatThrownBy(() -> retirer(decor, compte, "48000", "lim-10"))
            .isInstanceOf(Limits.LimitExceededException.class)
            .hasMessageContaining("mensuel");

        // Le compte negocie son plafond par operation, a deux : il l'emporte sur le produit.
        assertThatThrownBy(() -> database.inTransaction(c -> Limits.set(c, new Limits.Draft(
                decor.entityId(), compte, Limits.Kind.TRANSACTION, xof("500000"), J, null, ACTOR,
                ACTOR))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        database.inTransaction(c -> Limits.set(c, new Limits.Draft(
            decor.entityId(), compte, Limits.Kind.TRANSACTION, xof("500000"), J, null, ACTOR,
            APPROVER)));
        database.inTransaction(c -> Limits.set(c, new Limits.Draft(
            decor.entityId(), compte, Limits.Kind.MONTHLY, xof("2000000"), J, null, ACTOR,
            APPROVER)));
        dater(decor, J.plusDays(3));
        assertThatThrownBy(() -> retirer(decor, compte, "160000", "lim-11"))
            .as("le plafond journalier du produit tient toujours")
            .isInstanceOf(Limits.LimitExceededException.class)
            .hasMessageContaining("journalier");
        retirer(decor, compte, "140000", "lim-12");
        List<Limits.AccountLimit> plafonds = database.inTransaction(c -> Limits.ofAccount(c, compte));
        assertThat(plafonds).hasSize(2);
        // Deux plafonds d'une meme nature ne se chevauchent pas.
        assertThatThrownBy(() -> database.inTransaction(c -> Limits.set(c, new Limits.Draft(
                decor.entityId(), compte, Limits.Kind.TRANSACTION, xof("1"), J.plusDays(1), null,
                ACTOR, APPROVER))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deja en vigueur");
        // Un plafond nul interdit l'operation.
        database.inTransaction(c -> Limits.set(c, new Limits.Draft(
            decor.entityId(), autre, Limits.Kind.TRANSACTION, xof("0"), J, null, ACTOR, APPROVER)));
        assertThatThrownBy(() -> retirer(decor, autre, "1", "lim-13"))
            .isInstanceOf(Limits.LimitExceededException.class);
        assertThat(solde(compte)).isEqualTo(xof("1000000").minus(xof("100590")).minus(xof("40236"))
            .minus(xof("1590")).minus(xof("100590")).minus(xof("100590")).minus(xof("140590")));
    }

    @Test
    @DisplayName("sans plafond declare, rien ne borne ; un plafond ne se pose que sur un compte client, dans sa devise")
    void no_limit_by_default() {
        Decor decor = decor("NOL");
        produit(decor, "EP-NOL", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-NOL", "EP-NOL", client(decor.entityId(), "T-NOL"));
        verser(decor, compte, "5000000", "nol-0");
        retirer(decor, compte, "4000000", "nol-1");
        assertThatThrownBy(() -> database.inTransaction(c -> Limits.set(c, new Limits.Draft(
                decor.entityId(), decor.caisse().id(), Limits.Kind.DAILY, xof("1"), J, null,
                ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compte client");
        assertThatThrownBy(() -> database.inTransaction(c -> Limits.set(c, new Limits.Draft(
                decor.entityId(), compte, Limits.Kind.DAILY, Money.of("1", Currencies.EUR), J,
                null, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("le plafond en EUR");
        java.util.Optional<Limits.AccountLimit> aucun = database.inTransaction(
            c -> Limits.ofAccountOn(c, compte, Limits.Kind.DAILY, LocalDate.of(2026, 9, 15)));
        assertThat(aucun).isEmpty();
    }

    @Test
    @DisplayName("deux debits concurrents sous un plafond journalier : un seul passe, l'autre voit le premier")
    void concurrent_debits_are_serialised_under_a_limit() throws Exception {
        Decor decor = decor("CON");
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_DAILY_DEBIT_MAX, "150000");
        produit(decor, "EP-CON", "SAVINGS_ACCOUNT", parametres);
        UUID compte = ouvrir(decor, "CLI-CON", "EP-CON", client(decor.entityId(), "T-CON"));
        verser(decor, compte, "1000000", "con-0");

        var depart = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.function.Function<String, java.util.concurrent.Callable<Object>> tentative =
            key -> () -> {
                depart.await();
                try {
                    return retirer(decor, compte, "90000", key);
                } catch (RuntimeException e) {
                    return e;
                }
            };
        var premiere = executor.submit(tentative.apply("con-1"));
        var seconde = executor.submit(tentative.apply("con-2"));
        depart.countDown();
        List<Object> issues = List.of(premiere.get(), seconde.get());
        executor.shutdown();

        assertThat(issues).filteredOn(o -> o instanceof OperationsService.Receipt).hasSize(1);
        assertThat(issues).filteredOn(o -> o instanceof Limits.LimitExceededException).hasSize(1);
        assertThat(solde(compte)).isEqualTo(xof("1000000").minus(xof("90590")));
    }
}
