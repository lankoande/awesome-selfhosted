package io.corebanking.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.rate.Tier;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le parametrage produit se relit et se ferme.
 *
 * <p>Ce que ce test couvre n'existait pas : on redigeait une version sans pouvoir la retrouver, on
 * lisait un catalogue sans pouvoir relire un taux, et une version active sans terme interdisait a
 * jamais d'en activer une autre pour le meme code.
 */
class ProductVersionsIT extends ProductTestBase {

    private static final LocalDate D = BUSINESS_DATE;

    private Map<String, String> parameters(String rate) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put(ProductCatalog.P_RATE, rate);
        parameters.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
        parameters.put(ProductCatalog.P_SIDE, "CREDITOR");
        parameters.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
        parameters.put(ProductCatalog.P_DEBIT_ACCOUNT,
                       gl("GL-CH-" + UUID.randomUUID(), NormalBalance.DEBIT).id().toString());
        parameters.put(ProductCatalog.P_CREDIT_ACCOUNT,
                       gl("GL-CO-" + UUID.randomUUID()).id().toString());
        return parameters;
    }

    private ProductCatalog.Draft draft(String code, LocalDate from, LocalDate to, String rate) {
        return new ProductCatalog.Draft(ENTITY, code, "SAVINGS_ACCOUNT", "Epargne", "XOF",
                                        from, to, parameters(rate), List.of(), REDACTEUR);
    }

    private List<ProductCatalog.VersionSummary> versions(String code, String status) {
        return database.inTransaction(c -> ProductCatalog.versions(c, ENTITY, code, status));
    }

    private UUID redige(ProductCatalog.Draft draft) {
        return database.inTransaction(c -> ProductCatalog.createDraft(c, draft));
    }

    private UUID publie(ProductCatalog.Draft draft) {
        return database.inTransaction(c -> {
            UUID id = ProductCatalog.createDraft(c, draft);
            ProductCatalog.activate(c, id, VALIDEUR);
            return id;
        });
    }

    // ------------------------------------------------------------------ lecture

    @Test
    @DisplayName("les brouillons figurent dans les versions, jamais dans le catalogue ouvrable")
    void drafts_are_listed_but_not_openable() {
        UUID brouillon = redige(draft("EP-BROUILLON", D, null, "3"));

        List<ProductCatalog.VersionSummary> versions = versions("EP-BROUILLON", null);
        assertThat(versions).singleElement()
            .satisfies(version -> {
                assertThat(version.id()).isEqualTo(brouillon);
                assertThat(version.status()).isEqualTo("DRAFT");
                assertThat(version.createdBy()).isEqualTo(REDACTEUR);
                assertThat(version.createdAt()).isNotNull();
                assertThat(version.approvedBy()).isNull();
            });

        // Le guichet ne doit pas voir ce qui n'engage rien.
        List<ProductCatalog.Openable> ouvrables = database.inTransaction(
            c -> ProductCatalog.openable(c, ENTITY, D));
        assertThat(ouvrables).extracting(ProductCatalog.Openable::code)
            .doesNotContain("EP-BROUILLON");
    }

    @Test
    @DisplayName("le filtre par etat separe ce qui attend un second regard de ce qui est en vigueur")
    void versions_filter_by_status() {
        redige(draft("EP-FILTRE", D.plusYears(1), null, "3"));
        publie(draft("EP-FILTRE", D, D.plusMonths(6), "2"));

        List<ProductCatalog.VersionSummary> brouillons = versions("EP-FILTRE", "DRAFT");
        List<ProductCatalog.VersionSummary> actives = versions("EP-FILTRE", "ACTIVE");
        List<ProductCatalog.VersionSummary> toutes = versions("EP-FILTRE", null);

        assertThat(brouillons).singleElement()
            .extracting(ProductCatalog.VersionSummary::status).isEqualTo("DRAFT");
        assertThat(actives).singleElement()
            .extracting(ProductCatalog.VersionSummary::status).isEqualTo("ACTIVE");
        assertThat(toutes).hasSize(2);
    }

    @Test
    @DisplayName("une version se relit en entier : parametres et baremes")
    void a_version_reads_back_whole() {
        Map<String, String> parametres = parameters("4.25");
        UUID id = redige(new ProductCatalog.Draft(
            ENTITY, "EP-RELECTURE", "SAVINGS_ACCOUNT", "Epargne", "XOF", D, null, parametres,
            List.of(new Tier(BigDecimal.ZERO, new BigDecimal("1000000"), new BigDecimal("2")),
                    new Tier(new BigDecimal("1000000"), null, new BigDecimal("4"))),
            REDACTEUR));

        ProductCatalog.Version version = database
            .inTransaction(c -> ProductCatalog.version(c, ENTITY, id)).orElseThrow();

        assertThat(version.header().code()).isEqualTo("EP-RELECTURE");
        assertThat(version.parameters()).containsEntry(ProductCatalog.P_RATE, "4.25");
        assertThat(version.parameters()).containsAllEntriesOf(parametres);
        assertThat(version.tiers()).containsOnlyKeys(ProductCatalog.PURPOSE_INTEREST);
        assertThat(version.tiers().get(ProductCatalog.PURPOSE_INTEREST)).hasSize(2);
    }

    @Test
    @DisplayName("une version d'une autre entite n'existe pas pour l'appelant")
    void a_version_of_another_entity_is_invisible() {
        UUID id = redige(draft("EP-AILLEURS", D, null, "3"));
        java.util.Optional<ProductCatalog.Version> ailleurs = database.inTransaction(
            c -> ProductCatalog.version(c, UUID.randomUUID(), id));
        assertThat(ailleurs).isEmpty();
    }

    @Test
    @DisplayName("un bareme de commission se redige, et la famille le reconnait")
    void a_fee_schedule_can_be_drafted() {
        // Sans discriminant, une commission TIERED_ON_CLOSING_BALANCE etait declaree par la
        // famille et impossible a parametrer : l'activation exigeait un bareme FEE:<code> que
        // l'API ne savait pas creer.
        Map<String, String> parametres = parameters("3");
        String compte = gl("GL-COM-" + UUID.randomUUID()).id().toString();
        parametres.put("fee.codes", "TENUE");
        parametres.put("fee.TENUE.income_account", compte);
        parametres.put("fee.TENUE.basis", "TIERED_ON_CLOSING_BALANCE");

        UUID id = database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "EP-COMMISSION", "SAVINGS_ACCOUNT", "Epargne", "XOF", D, null, parametres,
                List.of(),
                Map.of("FEE:TENUE",
                       List.of(new Tier(BigDecimal.ZERO, new BigDecimal("500000"),
                                        new BigDecimal("1")),
                               new Tier(new BigDecimal("500000"), null, new BigDecimal("2")))),
                REDACTEUR));
            ProductCatalog.activate(c, version, VALIDEUR);
            return version;
        });

        ProductCatalog.Version relue = database
            .inTransaction(c -> ProductCatalog.version(c, ENTITY, id)).orElseThrow();
        assertThat(relue.tiers()).containsOnlyKeys("FEE:TENUE");
        assertThat(relue.header().status()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ fin de vie

    @Test
    @DisplayName("fermer une version sans terme libere le code pour la suivante")
    void closing_frees_the_code() {
        UUID premiere = publie(draft("EP-SUITE", D, null, "3"));

        // Sans fermeture, la contrainte d'exclusion refuse toute version suivante : le produit
        // ne pourrait plus jamais changer de parametrage.
        assertThatThrownBy(() -> publie(draft("EP-SUITE", D.plusDays(30), null, "5")))
            .isNotNull();

        database.inTransaction(c -> {
            ProductCatalog.close(c, ENTITY, premiere, D.plusDays(29), VALIDEUR);
            return null;
        });
        UUID seconde = publie(draft("EP-SUITE", D.plusDays(30), null, "5"));

        database.inTransaction(c -> {
            assertThat(ProductCatalog.resolveAt(c, ENTITY, "EP-SUITE", D.plusDays(29))
                .parameters().requireDecimal(ProductCatalog.P_RATE)).isEqualByComparingTo("3");
            assertThat(ProductCatalog.resolveAt(c, ENTITY, "EP-SUITE", D.plusDays(30))
                .parameters().requireDecimal(ProductCatalog.P_RATE)).isEqualByComparingTo("5");
            return null;
        });
        assertThat(seconde).isNotEqualTo(premiere);
    }

    @Test
    @DisplayName("une fermeture ne peut pas porter sur une date deja arretee")
    void closing_cannot_rewrite_a_processed_day() {
        UUID id = publie(draft("EP-PASSE", D.minusDays(30), null, "3"));

        assertThatThrownBy(() -> database.inTransaction(c -> {
            ProductCatalog.close(c, ENTITY, id, D.minusDays(1), VALIDEUR);
            return null;
        })).hasStackTraceContaining("un arrete deja produit resoudrait un autre parametrage");

        // La date comptable elle-meme est admise : cette journee n'est pas encore arretee.
        database.inTransaction(c -> {
            ProductCatalog.close(c, ENTITY, id, D, VALIDEUR);
            return null;
        });
        assertThat(database.inTransaction(c -> ProductCatalog.version(c, ENTITY, id))
            .orElseThrow().header().validTo()).isEqualTo(D);
    }

    @Test
    @DisplayName("un brouillon se retire ; une version activee, non — sa validite se ferme")
    void a_draft_is_withdrawn_an_active_version_is_closed() {
        UUID brouillon = redige(draft("EP-RETRAIT", D, null, "3"));
        database.inTransaction(c -> {
            ProductCatalog.withdrawDraft(c, ENTITY, brouillon, REDACTEUR);
            return null;
        });
        assertThat(database.inTransaction(c -> ProductCatalog.version(c, ENTITY, brouillon))
            .orElseThrow().header().status()).isEqualTo("WITHDRAWN");

        UUID active = publie(draft("EP-RETRAIT-ACTIF", D, null, "3"));
        assertThatThrownBy(() -> database.inTransaction(c -> {
            ProductCatalog.withdrawDraft(c, ENTITY, active, REDACTEUR);
            return null;
        })).hasStackTraceContaining("Une version activee ne se retire pas");
    }

    @Test
    @DisplayName("un brouillon retire disparait du catalogue ouvrable comme des versions actives")
    void a_withdrawn_draft_stays_readable() {
        UUID brouillon = redige(draft("EP-TRACE", D, null, "3"));
        database.inTransaction(c -> {
            ProductCatalog.withdrawDraft(c, ENTITY, brouillon, REDACTEUR);
            return null;
        });

        // Retire, pas supprime : ce qui a ete saisi une fois se relit, et explique pourquoi une
        // version attendue n'existe pas.
        assertThat(versions("EP-TRACE", "WITHDRAWN")).hasSize(1);
    }
}
