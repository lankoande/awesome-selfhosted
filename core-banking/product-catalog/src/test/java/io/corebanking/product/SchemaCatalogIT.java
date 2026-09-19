package io.corebanking.product;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.SchemaValidator;
import io.corebanking.schema.TemplateLine;
import io.corebanking.schema.expr.EvaluationContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaCatalogIT extends ProductTestBase {

    private static final LocalDate D = LocalDate.of(2026, 9, 1);
    private static JdbcPostingService postingService;

    @BeforeAll
    static void prepareLedger() {
        SchemaMigrator.ensurePartitions(database, D.minusMonths(1), D.plusMonths(2));
        database.inTransaction(c -> {
            Entities.openPeriod(c, ENTITY, D, D.plusMonths(1).minusDays(1));
            return null;
        });
        postingService = new JdbcPostingService(database);
    }

    /** Commission TVA comprise, ecrite de sorte que les arrondis se recomposent. */
    private AccountingSchema commissionSaine() {
        return AccountingSchema.of("EP-FRAIS", 1)
            .on(EventTemplate.of("MAINTENANCE_FEE")
                .derive("total", "round(base + base * taux_tva, 0)")
                .derive("tva", "round(base * taux_tva, 0)")
                .derive("net", "total - tva")
                .line(TemplateLine.debit("CONTRACT", "total", "Frais de tenue de compte"))
                .line(TemplateLine.credit("GL:70611", "net", "Commissions percues"))
                .line(TemplateLine.credit("GL:44320", "tva", "TVA collectee").onlyIf("tva > 0"))
                .build())
            .build();
    }

    private AccountingSchema commissionNaive() {
        return AccountingSchema.of("EP-NAIF", 1)
            .on(EventTemplate.of("MAINTENANCE_FEE")
                .derive("tva", "base * taux_tva")
                .derive("total", "base + tva")
                .line(TemplateLine.debit("CONTRACT", "round(total, 0)", "Frais"))
                .line(TemplateLine.credit("GL:70611", "round(base, 0)", "Commissions"))
                .line(TemplateLine.credit("GL:44320", "round(tva, 0)", "TVA").onlyIf("tva > 0"))
                .build())
            .build();
    }

    private SchemaCatalog.Draft draft(String code, AccountingSchema schema) {
        return new SchemaCatalog.Draft(ENTITY, code, "Frais", XOF, D, null, schema, REDACTEUR);
    }

    @Test
    @DisplayName("un schema desequilibre par les arrondis ne peut pas etre enregistre")
    void an_unbalanced_schema_cannot_even_be_stored() {
        assertThatThrownBy(() -> database.inTransaction(c ->
            SchemaCatalog.createDraft(c, draft("EP-NAIF", commissionNaive()))))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class)
            .hasMessageContaining("Contre-exemple");

        // Et rien n'a ete ecrit : la base ne contient aucun schema faux en sommeil.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM accounting_schema WHERE code = 'EP-NAIF'")) {
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("un schema sain se stocke, s'active a quatre yeux, et se relit a l'identique")
    void a_sound_schema_round_trips() {
        UUID id = database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, draft("EP-FRAIS", commissionSaine()));
            SchemaCatalog.activate(c, created, VALIDEUR);
            return created;
        });
        assertThat(id).isNotNull();

        AccountingSchema relu = database.inTransaction(c ->
            SchemaCatalog.resolveAt(c, ENTITY, "EP-FRAIS", D.plusDays(10)));

        var template = relu.requireTemplate("MAINTENANCE_FEE");
        assertThat(template.lines()).hasSize(3);
        assertThat(template.derivations().keySet()).containsExactly("total", "tva", "net");
        assertThat(template.lines().get(0).amount().source()).isEqualTo("total");
        assertThat(template.lines().get(2).condition().source()).isEqualTo("tva > 0");
    }

    @Test
    @DisplayName("le redacteur d'un schema ne peut pas l'activer lui-meme")
    void maker_cannot_activate_their_own_schema() {
        assertThatThrownBy(() -> database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, draft("EP-MAKER", commissionSaine()));
            SchemaCatalog.activate(c, created, REDACTEUR);
            return null;
        })).hasStackTraceContaining("ck_schema_approval");
    }

    @Test
    @DisplayName("de bout en bout : un evenement metier devient trois ecritures equilibrees")
    void end_to_end_a_business_event_becomes_balanced_entries() {
        Account client = customer("CLI-600");
        Account produits = gl("GL-70611-600", NormalBalance.CREDIT);
        Account tva = gl("GL-44320-600", NormalBalance.CREDIT);

        database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, draft("EP-E2E", commissionSaine()));
            SchemaCatalog.activate(c, created, VALIDEUR);
            return null;
        });

        AccountResolver resolveur = reference -> switch (reference.kind()) {
            case CONTRACT -> client.id();
            case GL -> switch (reference.value()) {
                case "70611" -> produits.id();
                case "44320" -> tva.id();
                default -> throw new AccountResolver.UnresolvableAccountException(
                    reference, "absent du plan comptable de l'entite");
            };
            default -> throw new AccountResolver.UnresolvableAccountException(reference, "non gere");
        };

        // L'evenement metier : une commission de 1 000 XOF, TVA a 18 %.
        AccountingSchema schema = database.inTransaction(c ->
            SchemaCatalog.resolveAt(c, ENTITY, "EP-E2E", D.plusDays(5)));

        List<PostingLine> lignes = SchemaEngine.linesFor(
            schema.requireTemplate("MAINTENANCE_FEE"),
            EvaluationContext.builder().put("base", "1000").put("taux_tva", "0.18").build(),
            resolveur, XOF, D.plusDays(5));

        postingService.post(PostingCommand.online(
            IdempotencyKey.of("fee-600"), ENTITY, D.plusDays(5), "MAINTENANCE_FEE", REDACTEUR,
            lignes));

        database.inTransaction(c -> {
            // Le client est debite du total, la banque credite sa commission et la TVA collectee.
            assertThat(Balances.current(c, client.id())).isEqualTo(Money.of("-1180", XOF));
            assertThat(Balances.current(c, produits.id())).isEqualTo(Money.of("1000", XOF));
            assertThat(Balances.current(c, tva.id())).isEqualTo(Money.of("180", XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("un schema non couvert a la date demandee est une erreur, pas un repli")
    void an_uncovered_date_is_an_error() {
        database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, new SchemaCatalog.Draft(
                ENTITY, "EP-DATE", "Frais", XOF, D.plusDays(10), null, commissionSaine(),
                REDACTEUR));
            SchemaCatalog.activate(c, created, VALIDEUR);
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(c ->
            SchemaCatalog.resolveAt(c, ENTITY, "EP-DATE", D)))
            .isInstanceOf(ProductNotFoundException.class)
            .hasMessageContaining("ne peut pas se rabattre");
    }

    // ------------------------------------------------------------------ relecture et fin de vie

    @Test
    @DisplayName("un brouillon se retrouve : la liste et le detail rendent ce qui a ete ecrit")
    void a_draft_can_be_found_again() {
        UUID created = database.inTransaction(c ->
            SchemaCatalog.createDraft(c, draft("EP-RELU", commissionSaine())));

        List<SchemaCatalog.Summary> brouillons = database.inTransaction(c ->
            SchemaCatalog.summaries(c, ENTITY, "EP-RELU", "DRAFT"));
        assertThat(brouillons).extracting(SchemaCatalog.Summary::id).containsExactly(created);
        assertThat(brouillons.get(0).status()).isEqualTo("DRAFT");

        SchemaCatalog.Detail detail = database.inTransaction(c ->
            SchemaCatalog.detail(c, ENTITY, created)).orElseThrow();
        assertThat(detail.events()).hasSize(1);
        SchemaCatalog.EventView evenement = detail.events().get(0);
        assertThat(evenement.eventType()).isEqualTo("MAINTENANCE_FEE");
        assertThat(evenement.lines()).hasSize(3);
        assertThat(evenement.lines().get(2).condition()).isEqualTo("tva > 0");
        // Les grandeurs a fournir sont deduites des expressions, jamais saisies a cote.
        assertThat(evenement.variables()).containsExactlyInAnyOrder("base", "taux_tva");
    }

    /** Un schema d'une autre entite ne se lit pas, meme avec son identifiant exact. */
    @Test
    @DisplayName("le detail verifie l'entite, il ne la suppose pas")
    void detail_checks_the_entity() {
        UUID created = database.inTransaction(c ->
            SchemaCatalog.createDraft(c, draft("EP-AUTRE", commissionSaine())));

        java.util.Optional<SchemaCatalog.Detail> ailleurs = database.inTransaction(c ->
            SchemaCatalog.detail(c, UUID.randomUUID(), created));
        assertThat(ailleurs).isEmpty();
    }

    /**
     * Le fait qui justifie l'existence de la fermeture : tant que le schema en vigueur n'a pas de
     * terme, la contrainte d'exclusion interdit d'activer son successeur. Sans fermeture, un code
     * de schema est fige pour toujours.
     */
    @Test
    @DisplayName("fermer la validite est ce qui permet d'activer le schema suivant")
    void closing_is_what_makes_the_next_version_possible() {
        UUID premier = database.inTransaction(c -> {
            UUID id = SchemaCatalog.createDraft(c, draft("EP-SUITE", commissionSaine()));
            SchemaCatalog.activate(c, id, VALIDEUR);
            return id;
        });
        UUID second = database.inTransaction(c -> SchemaCatalog.createDraft(c,
            new SchemaCatalog.Draft(ENTITY, "EP-SUITE", "Frais", XOF, D.plusDays(20), null,
                                    commissionSaine(), REDACTEUR)));

        // Sans fermeture du premier, l'activation du second croise une validite active.
        assertThatThrownBy(() -> database.inTransaction(c -> {
            SchemaCatalog.activate(c, second, VALIDEUR);
            return null;
        })).hasStackTraceContaining("ex_schema_no_overlap");

        database.inTransaction(c -> {
            SchemaCatalog.close(c, ENTITY, premier, D.plusDays(19));
            SchemaCatalog.activate(c, second, VALIDEUR);
            return null;
        });

        // Chaque date resout desormais le schema de sa periode, et un seul.
        List<SchemaCatalog.Summary> actifs = database.inTransaction(c ->
            SchemaCatalog.summaries(c, ENTITY, "EP-SUITE", "ACTIVE"));
        assertThat(actifs).hasSize(2);
        assertThatCode(() -> database.inTransaction(c ->
            SchemaCatalog.resolveAt(c, ENTITY, "EP-SUITE", D.plusDays(25))))
            .doesNotThrowAnyException();
    }

    /**
     * Une fermeture anterieure a la date comptable changerait l'imputation d'un arrete deja
     * produit : au rejeu, un autre schema se resoudrait, donc d'autres ecritures.
     */
    @Test
    @DisplayName("une fermeture ne peut pas preceder la date comptable de l'entite")
    void closing_cannot_predate_the_business_date() {
        UUID id = database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, draft("EP-PASSE", commissionSaine()));
            SchemaCatalog.activate(c, created, VALIDEUR);
            return created;
        });

        assertThatThrownBy(() -> database.inTransaction(c -> {
            SchemaCatalog.close(c, ENTITY, id, BUSINESS_DATE.minusDays(1));
            return null;
        })).isInstanceOf(IllegalArgumentException.class)
           .hasMessageContaining("arrete deja produit");
    }

    @Test
    @DisplayName("un brouillon retire garde la signature de celui qui l'a retire")
    void a_withdrawn_draft_keeps_its_signature() {
        UUID id = database.inTransaction(c ->
            SchemaCatalog.createDraft(c, draft("EP-RETIRE", commissionSaine())));

        database.inTransaction(c -> {
            SchemaCatalog.withdrawDraft(c, ENTITY, id, REDACTEUR);
            return null;
        });

        SchemaCatalog.Summary retire = database.inTransaction(c ->
            SchemaCatalog.summaries(c, ENTITY, "EP-RETIRE", null)).get(0);
        assertThat(retire.status()).isEqualTo("WITHDRAWN");
        assertThat(retire.withdrawnBy()).isEqualTo(REDACTEUR);
        assertThat(retire.withdrawnAt()).isNotNull();
    }

    /** Un schema active ne se retire pas : sa validite se ferme, et la nuance est comptable. */
    @Test
    @DisplayName("un schema active ne se retire pas")
    void an_active_schema_cannot_be_withdrawn() {
        UUID id = database.inTransaction(c -> {
            UUID created = SchemaCatalog.createDraft(c, draft("EP-ACTIF", commissionSaine()));
            SchemaCatalog.activate(c, created, VALIDEUR);
            return created;
        });

        assertThatThrownBy(() -> database.inTransaction(c -> {
            SchemaCatalog.withdrawDraft(c, ENTITY, id, REDACTEUR);
            return null;
        })).isInstanceOf(IllegalStateException.class)
           .hasMessageContaining("sa validite se ferme");
    }
}
