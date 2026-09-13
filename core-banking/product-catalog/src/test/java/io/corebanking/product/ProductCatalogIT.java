package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.rate.Tier;
import io.corebanking.interest.rate.TieringMode;
import io.corebanking.kernel.money.Currencies;
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
            from, to,
            Map.of(ProductCatalog.P_RATE, rate,
                   ProductCatalog.P_DAY_COUNT, "ACT_365",
                   ProductCatalog.P_SIDE, "CREDITOR",
                   ProductCatalog.P_DEBIT_ACCOUNT, UUID.randomUUID().toString(),
                   ProductCatalog.P_CREDIT_ACCOUNT, UUID.randomUUID().toString()),
            List.of(), REDACTEUR);
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
            Map.of(ProductCatalog.P_DAY_COUNT, "ACT_365",
                   ProductCatalog.P_SIDE, "CREDITOR",
                   ProductCatalog.P_TIERING_MODE, TieringMode.PROGRESSIVE.name(),
                   ProductCatalog.P_DEBIT_ACCOUNT, UUID.randomUUID().toString(),
                   ProductCatalog.P_CREDIT_ACCOUNT, UUID.randomUUID().toString()),
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
            Map.of(ProductCatalog.P_DAY_COUNT, "ACT_365", ProductCatalog.P_SIDE, "CREDITOR",
                   ProductCatalog.P_DEBIT_ACCOUNT, UUID.randomUUID().toString(),
                   ProductCatalog.P_CREDIT_ACCOUNT, UUID.randomUUID().toString()),
            List.of(Tier.of("0", "1000000", "2"), Tier.of("2000000", null, "3")), REDACTEUR);

        assertThatThrownBy(() -> publish(lacunaire))
            .hasStackTraceContaining("non contigues");
    }

    @Test
    @DisplayName("un parametre absent est nomme explicitement, produit compris")
    void missing_parameter_is_named() {
        var incomplet = new ProductCatalog.Draft(ENTITY, "EP-INCOMPLET", "SAVINGS_ACCOUNT",
            "Epargne", "XOF", D, null,
            Map.of(ProductCatalog.P_DAY_COUNT, "ACT_365"), List.of(), REDACTEUR);
        publish(incomplet);

        assertThatThrownBy(() -> database.inTransaction(c ->
            ProductCatalog.resolveAt(c, ENTITY, "EP-INCOMPLET", D)
                .parameters().requireDecimal(ProductCatalog.P_RATE)))
            .isInstanceOf(ParameterSet.MissingParameterException.class)
            .hasMessageContaining("interest.rate")
            .hasMessageContaining("EP-INCOMPLET");
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
