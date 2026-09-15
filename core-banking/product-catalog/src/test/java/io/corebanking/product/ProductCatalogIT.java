package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductCatalogIT extends ProductTestBase {

    private static final LocalDate D = LocalDate.of(2026, 1, 1);

    private ProductCatalog.Draft draft(String code, LocalDate from, LocalDate to, String rate) {
        return new ProductCatalog.Draft(ENTITY, code, "SAVINGS_ACCOUNT", "Epargne", "XOF",
            from, to, interestParameters(rate), List.of(), REDACTEUR);
    }

    /** Parametres d'interets complets, sur des comptes generaux qui existent. */
    private static Map<String, String> interestParameters(String rate) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        if (rate != null) {
            parameters.put(ProductCatalog.P_RATE, rate);
        }
        parameters.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parameters.put(ProductCatalog.P_SIDE, "CREDITOR");
        parameters.put(ProductCatalog.P_DEBIT_ACCOUNT,
                       gl("GL-CHARGES-" + UUID.randomUUID(), NormalBalance.DEBIT).id().toString());
        parameters.put(ProductCatalog.P_CREDIT_ACCOUNT,
                       gl("GL-COURUS-" + UUID.randomUUID()).id().toString());
        return parameters;
    }

    private UUID publish(ProductCatalog.Draft draft) {
        return database.inTransaction(c -> {
            UUID id = ProductCatalog.createDraft(c, draft);
            ProductCatalog.activate(c, id, VALIDEUR);
            return id;
        });
    }

    @Test
    @DisplayName("la resolution rend la version en vigueur a la date demandee, pas la plus recente")
    void resolution_is_dated() {
        publish(draft("EP-RESOLUTION", D, D.plusDays(89), "3"));
        publish(draft("EP-RESOLUTION", D.plusDays(90), null, "6"));

        database.inTransaction(c -> {
            assertThat(ProductCatalog.resolveAt(c, ENTITY, "EP-RESOLUTION", D.plusDays(10))
                .parameters().requireDecimal(ProductCatalog.P_RATE)).isEqualByComparingTo("3");
            assertThat(ProductCatalog.resolveAt(c, ENTITY, "EP-RESOLUTION", D.plusDays(89))
                .parameters().requireDecimal(ProductCatalog.P_RATE)).isEqualByComparingTo("3");
            assertThat(ProductCatalog.resolveAt(c, ENTITY, "EP-RESOLUTION", D.plusDays(90))
                .parameters().requireDecimal(ProductCatalog.P_RATE)).isEqualByComparingTo("6");
            return null;
        });
    }

    @Test
    @DisplayName("deux versions actives qui se chevauchent sont refusees par la base")
    void overlapping_versions_are_rejected() {
        publish(draft("EP-OVERLAP", D, D.plusDays(89), "3"));

        assertThatThrownBy(() -> publish(draft("EP-OVERLAP", D.plusDays(30), null, "6")))
            .hasStackTraceContaining("ex_no_overlap");
    }

    @Test
    @DisplayName("le redacteur d'un parametrage ne peut pas l'activer lui-meme")
    void maker_cannot_be_checker() {
        assertThatThrownBy(() -> database.inTransaction(c -> {
            UUID id = ProductCatalog.createDraft(c, draft("EP-MAKER", D, null, "3"));
            ProductCatalog.activate(c, id, REDACTEUR);     // meme personne
            return null;
        })).hasStackTraceContaining("ck_approval");
    }

    @Test
    @DisplayName("une version en projet reste invisible des traitements")
    void draft_is_not_resolvable() {
        database.inTransaction(c -> ProductCatalog.createDraft(c, draft("EP-DRAFT", D, null, "3")));

        assertThatThrownBy(() -> database.inTransaction(c ->
            ProductCatalog.resolveAt(c, ENTITY, "EP-DRAFT", D.plusDays(1))))
            .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("une periode non couverte est une erreur, jamais un repli sur une autre version")
    void uncovered_period_is_an_error() {
        publish(draft("EP-GAP", D.plusDays(10), null, "3"));

        assertThatThrownBy(() -> database.inTransaction(c ->
            ProductCatalog.resolveAt(c, ENTITY, "EP-GAP", D)))
            .isInstanceOf(ProductNotFoundException.class)
            .hasMessageContaining("ne peut pas se rabattre");
    }

    @Test
    @DisplayName("un bareme par tranches est relu tel qu'il a ete saisi")
    void tiered_schedule_round_trips() {
        var avecTranches = new ProductCatalog.Draft(ENTITY, "EP-TIERS", "SAVINGS_ACCOUNT",
            "Epargne par tranches", "XOF", D, null,
            withTiering(interestParameters(null)),
            List.of(Tier.of("0", "5000000", "2"), Tier.of("5000000", null, "3")), REDACTEUR);
        publish(avecTranches);

        database.inTransaction(c -> {
            var version = ProductCatalog.resolveAt(c, ENTITY, "EP-TIERS", D.plusDays(5));
            assertThat(version.tieredSchedule()).isPresent();
            // 5 M a 2 % + 1 M a 3 % sur un an.
            assertThat(version.tieredSchedule().orElseThrow()
                .accrue(Money.of("6000000", Currencies.XOF), BigDecimal.ONE))
                .isEqualTo(Money.of("130000", Currencies.XOF));
            return null;
        });
    }

    @Test
    @DisplayName("un bareme lacunaire est refuse au deploiement du parametrage")
    void gapped_tiers_are_refused_at_deployment() {
        var lacunaire = new ProductCatalog.Draft(ENTITY, "EP-LACUNE", "SAVINGS_ACCOUNT", "Epargne",
            "XOF", D, null,
            interestParameters(null),
            List.of(Tier.of("0", "1000000", "2"), Tier.of("2000000", null, "3")), REDACTEUR);

        assertThatThrownBy(() -> publish(lacunaire))
            .hasStackTraceContaining("non contigues");
    }

    @Test
    @DisplayName("un parametrage incomplet est refuse a l'activation, tout ce qui manque nomme")
    void incomplete_parameters_are_refused_at_activation() {
        var incomplet = new ProductCatalog.Draft(ENTITY, "EP-INCOMPLET", "SAVINGS_ACCOUNT",
            "Epargne", "XOF", D, null,
            Map.of(ProductCatalog.P_DAY_COUNT, "ACT_365"), List.of(), REDACTEUR);

        // Le meme produit s'activait jusqu'ici sans rien dire, et l'etape d'accrual — bloquante —
        // echouait la nuit suivante. Le refus intervient maintenant devant celui qui parametre.
        assertThatThrownBy(() -> publish(incomplet))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .hasMessageContaining("EP-INCOMPLET")
            .hasMessageContaining("interest.side")
            .hasMessageContaining("interest.debit_account")
            .hasMessageContaining("interest.credit_account")
            .hasMessageContaining("aucun de [interest.rate, tier:INTEREST]");

        // Les manques sont restitues tous ensemble : s'arreter au premier obligerait a redeployer
        // autant de fois qu'il manque de lignes, et le controle finirait par etre desactive.
        assertThatThrownBy(() -> publish(incomplet))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                ProductFamily.IncompleteProductException.class))
            .extracting(ProductFamily.IncompleteProductException::problems)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .hasSize(4);

        // Le brouillon subsiste : il n'est simplement resolvable par aucun traitement.
        assertThatThrownBy(() -> database.inTransaction(c ->
            ProductCatalog.resolveAt(c, ENTITY, "EP-INCOMPLET", D)))
            .isInstanceOf(ProductNotFoundException.class);
    }

    private static Map<String, String> withTiering(Map<String, String> parameters) {
        parameters.put(ProductCatalog.P_TIERING_MODE, TieringMode.PROGRESSIVE.name());
        return parameters;
    }

    @Test
    @DisplayName("un compte inconnu cite par le parametrage est refuse a l'activation, nomme")
    void an_unknown_account_is_refused_at_activation() {
        Map<String, String> parameters = interestParameters("3");
        parameters.put(ProductCatalog.P_CREDIT_ACCOUNT, UUID.randomUUID().toString());
        var fantome = new ProductCatalog.Draft(ENTITY, "EP-FANTOME", "SAVINGS_ACCOUNT", "Epargne",
                                               "XOF", D, null, parameters, List.of(), REDACTEUR);

        // La premiere ecriture d'interets aurait ete refusee par le ledger — de nuit, sur une
        // etape bloquante. Le refus intervient maintenant devant celui qui parametre.
        assertThatThrownBy(() -> publish(fantome))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .hasMessageContaining("interest.credit_account")
            .hasMessageContaining("inconnu");
    }

    @Test
    @DisplayName("un compte d'une autre entite, ou un compte client, ne recoit pas les produits")
    void a_foreign_or_customer_account_is_refused() {
        UUID filiale = UUID.randomUUID();
        Account ailleurs = database.inTransaction(c -> {
            Entities.insertLegalEntity(c, filiale, "BANK-SN", "Filiale", "SN", Currencies.XOF,
                                       BUSINESS_DATE);
            Account account = new Account(UUID.randomUUID(), filiale, "GL-SN-COURUS",
                                          AccountKind.GL, NormalBalance.CREDIT, Currencies.XOF,
                                          true, false, 1, AccountStatus.ACTIVE);
            Accounts.create(c, account, BUSINESS_DATE);
            return account;
        });
        Map<String, String> parameters = interestParameters("3");
        parameters.put(ProductCatalog.P_CREDIT_ACCOUNT, ailleurs.id().toString());
        assertThatThrownBy(() -> publish(new ProductCatalog.Draft(ENTITY, "EP-SN", "SAVINGS_ACCOUNT",
            "Epargne", "XOF", D, null, parameters, List.of(), REDACTEUR)))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .hasMessageContaining("appartient a une autre entite juridique");

        // Un compte client comme compte de produit : les interets de tous les livrets iraient a
        // un seul client. Un compte general est attendu.
        Account client = database.inTransaction(c -> {
            Account account = new Account(UUID.randomUUID(), ENTITY, "CLI-PRODUITS",
                                          AccountKind.CUSTOMER, NormalBalance.CREDIT,
                                          Currencies.XOF, true, false, 1, AccountStatus.ACTIVE);
            Accounts.create(c, account, BUSINESS_DATE);
            return account;
        });
        Map<String, String> surClient = interestParameters("3");
        surClient.put(ProductCatalog.P_CREDIT_ACCOUNT, client.id().toString());
        assertThatThrownBy(() -> publish(new ProductCatalog.Draft(ENTITY, "EP-CLI", "SAVINGS_ACCOUNT",
            "Epargne", "XOF", D, null, surClient, List.of(), REDACTEUR)))
            .isInstanceOf(ProductFamily.IncompleteProductException.class)
            .hasMessageContaining("de nature CUSTOMER, un compte general est attendu");
    }

    @Test
    @DisplayName("un compte ne se rattache pas a un produit d'une autre devise, ni a un produit absent")
    void assignment_checks_currency_and_existence() {
        publish(draft("EP-XOF", D, null, "3"));
        Account enEuros = database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.EUR, "Euro");
            Account account = new Account(UUID.randomUUID(), ENTITY, "CLI-EUR",
                                          AccountKind.CUSTOMER, NormalBalance.CREDIT,
                                          Currencies.EUR, true, false, 1, AccountStatus.ACTIVE);
            Accounts.create(c, account, BUSINESS_DATE);
            return account;
        });

        // Le compte serait remunere au bareme de l'un sur les soldes de l'autre, et rien dans
        // l'ecriture ne le dirait.
        assertThatThrownBy(() -> database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, enEuros.id(), "EP-XOF", D, null);
            return null;
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("est en XOF alors que le compte CLI-EUR est en EUR");

        Account livret = gl("CLI-LIVRET");
        assertThatThrownBy(() -> database.inTransaction(c -> {
            ProductCatalog.assignProduct(c, livret.id(), "EP-INEXISTANT", D, null);
            return null;
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("Aucun produit EP-INEXISTANT");
    }

    @Test
    @DisplayName("un type de produit hors catalogue est refuse des la saisie")
    void unknown_family_is_refused_upfront() {
        var exotique = new ProductCatalog.Draft(ENTITY, "EP-EXOTIQUE", "COMPTE_EXOTIQUE",
            "Produit sans famille", "XOF", D, null, Map.of(), List.of(), REDACTEUR);

        // Le refus a la saisie plutot qu'a l'activation : decouvrir la faute de frappe apres avoir
        // renseigne trente parametres coute le double.
        assertThatThrownBy(() -> database.inTransaction(c ->
            ProductCatalog.createDraft(c, exotique)))
            .isInstanceOf(ProductFamilies.UnknownFamilyException.class)
            .hasMessageContaining("COMPTE_EXOTIQUE")
            .hasMessageContaining("SAVINGS_ACCOUNT");
    }

    @Test
    @DisplayName("le journal du parametrage est immuable")
    void product_audit_is_immutable() {
        publish(draft("EP-AUDIT", D, null, "3"));

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var st = c.createStatement()) {
                st.executeUpdate("DELETE FROM product_audit");
                return null;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        })).hasStackTraceContaining("Le journal du parametrage est immuable");
    }
}
