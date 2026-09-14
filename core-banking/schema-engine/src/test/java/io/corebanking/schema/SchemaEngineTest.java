package io.corebanking.schema;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.schema.expr.EvaluationContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaEngineTest {

    private static final LocalDate VALEUR = LocalDate.of(2026, 9, 14);

    private static final UUID COMPTE_CLIENT = UUID.randomUUID();
    private static final UUID CAISSE        = UUID.randomUUID();
    private static final UUID PRODUITS      = UUID.randomUUID();
    private static final UUID TVA_COLLECTEE = UUID.randomUUID();

    private final AccountResolver resolveur = reference -> switch (reference.kind()) {
        case CONTRACT -> COMPTE_CLIENT;
        case RESOLVER -> CAISSE;
        case GL -> switch (reference.value()) {
            case "70611" -> PRODUITS;
            case "44320" -> TVA_COLLECTEE;
            default -> throw new AccountResolver.UnresolvableAccountException(
                reference, "code absent du plan comptable de l'entite");
        };
        default -> throw new AccountResolver.UnresolvableAccountException(reference, "non gere");
    };

    /** Versement au guichet : la traduction la plus simple. */
    private EventTemplate versement() {
        return EventTemplate.of("DEPOSIT")
            .line(TemplateLine.debit("RESOLVE:cash", "montant", "Versement"))
            .line(TemplateLine.credit("CONTRACT", "montant", "Versement"))
            .build();
    }

    /**
     * Commission de tenue de compte, TVA comprise.
     *
     * <p>Le total est arrondi d'abord, la TVA ensuite, et le net s'en deduit : ainsi la somme des
     * composantes egale le total <b>par construction</b>, quels que soient les arrondis.
     */
    private EventTemplate commission() {
        return EventTemplate.of("MAINTENANCE_FEE")
            .derive("total", "round(base + base * taux_tva, 0)")
            .derive("tva", "round(base * taux_tva, 0)")
            .derive("net", "total - tva")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais de tenue de compte"))
            .line(TemplateLine.credit("GL:70611", "net", "Commissions percues"))
            .line(TemplateLine.credit("GL:44320", "tva", "TVA collectee").onlyIf("tva > 0"))
            .build();
    }

    @Test
    @DisplayName("un versement produit deux lignes equilibrees sur les bons comptes")
    void a_deposit_produces_two_balanced_lines() {
        List<PostingLine> lignes = SchemaEngine.linesFor(versement(),
            EvaluationContext.builder().put("montant", "250000").build(),
            resolveur, XOF, VALEUR);

        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).accountId()).isEqualTo(CAISSE);
        assertThat(lignes.get(0).direction()).isEqualTo(Direction.DEBIT);
        assertThat(lignes.get(1).accountId()).isEqualTo(COMPTE_CLIENT);
        assertThat(lignes.get(1).amount()).isEqualTo(Money.of("250000", XOF));
    }

    @Test
    @DisplayName("une commission soumise a TVA produit trois lignes, dont la somme tombe juste")
    void a_taxed_fee_produces_three_lines_that_add_up() {
        List<PostingLine> lignes = SchemaEngine.linesFor(commission(),
            EvaluationContext.builder().put("base", "1000").put("taux_tva", "0.18").build(),
            resolveur, XOF, VALEUR);

        assertThat(lignes).hasSize(3);
        assertThat(lignes.get(0).amount()).isEqualTo(Money.of("1180", XOF));   // total debite
        assertThat(lignes.get(1).amount()).isEqualTo(Money.of("1000", XOF));   // net
        assertThat(lignes.get(2).amount()).isEqualTo(Money.of("180", XOF));    // TVA
        assertThat(lignes.get(1).amount().plus(lignes.get(2).amount()))
            .isEqualTo(lignes.get(0).amount());
    }

    @Test
    @DisplayName("une operation exoneree ne produit pas de ligne de TVA a zero")
    void an_exempt_operation_produces_no_zero_vat_line() {
        List<PostingLine> lignes = SchemaEngine.linesFor(commission(),
            EvaluationContext.builder().put("base", "1000").put("taux_tva", "0").build(),
            resolveur, XOF, VALEUR);

        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).amount()).isEqualTo(Money.of("1000", XOF));
    }

    @Test
    @DisplayName("un montant non comptabilisable est refuse, il n'est pas arrondi en silence")
    void a_non_bookable_amount_is_refused_not_silently_rounded() {
        EventTemplate sansArrondi = EventTemplate.of("BAD_FEE")
            .line(TemplateLine.debit("CONTRACT", "base * taux_tva", "Frais"))
            .line(TemplateLine.credit("GL:70611", "base * taux_tva", "Frais"))
            .build();

        assertThatThrownBy(() -> SchemaEngine.linesFor(sansArrondi,
            EvaluationContext.builder().put("base", "1000").put("taux_tva", "0.1855").build(),
            resolveur, XOF, VALEUR))
            .isInstanceOf(SchemaEngine.InvalidSchemaAmountException.class)
            .hasMessageContaining("non comptabilisable")
            .hasMessageContaining("employez round");
    }

    @Test
    @DisplayName("un montant negatif revele une erreur de sens, et est refuse")
    void a_negative_amount_reveals_a_direction_error() {
        EventTemplate inverse = EventTemplate.of("BAD_SIGN")
            .line(TemplateLine.debit("CONTRACT", "0 - montant", "Mauvais sens"))
            .line(TemplateLine.credit("GL:70611", "montant", "Frais"))
            .build();

        assertThatThrownBy(() -> SchemaEngine.linesFor(inverse,
            EvaluationContext.builder().put("montant", "1000").build(), resolveur, XOF, VALEUR))
            .isInstanceOf(SchemaEngine.InvalidSchemaAmountException.class)
            .hasMessageContaining("le sens est porte par la direction");
    }

    @Test
    @DisplayName("un compte absent du plan comptable de l'entite est signale, pas contourne")
    void an_unresolvable_account_is_reported() {
        EventTemplate inconnu = EventTemplate.of("UNKNOWN_ACCOUNT")
            .line(TemplateLine.debit("CONTRACT", "montant", null))
            .line(TemplateLine.credit("GL:99999", "montant", null))
            .build();

        assertThatThrownBy(() -> SchemaEngine.linesFor(inconnu,
            EvaluationContext.builder().put("montant", "1000").build(), resolveur, XOF, VALEUR))
            .isInstanceOf(AccountResolver.UnresolvableAccountException.class)
            .hasMessageContaining("GL:99999");
    }

    @Test
    @DisplayName("un evenement sans traduction est refuse explicitement")
    void an_untranslated_event_is_refused() {
        AccountingSchema schema = AccountingSchema.of("EP-PLUS", 1).on(versement()).build();

        assertThatThrownBy(() -> schema.requireTemplate("WITHDRAWAL"))
            .isInstanceOf(AccountingSchema.UnknownEventException.class)
            .hasMessageContaining("ecart sans origine");
    }

    @Test
    @DisplayName("un schema a une seule ligne n'est pas de la partie double")
    void a_single_line_template_is_refused() {
        assertThatThrownBy(() -> new EventTemplate("X", new java.util.LinkedHashMap<>(),
            List.of(TemplateLine.debit("CONTRACT", "montant", null))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("partie double");
    }

    @Test
    @DisplayName("les variables a fournir sont celles qui ne sont pas calculees par le schema")
    void free_variables_exclude_derived_ones() {
        assertThat(commission().freeVariables()).containsExactlyInAnyOrder("base", "taux_tva");
        assertThat(versement().freeVariables()).containsExactly("montant");
    }

    @Test
    @DisplayName("les derivations s'appuient sur les precedentes, dans l'ordre declare")
    void derivations_chain_in_declaration_order() {
        EvaluationContext enrichi = commission().derive(
            EvaluationContext.of(Map.of("base", new java.math.BigDecimal("1000"),
                                        "taux_tva", new java.math.BigDecimal("0.18"))));

        assertThat(enrichi.require("total")).isEqualByComparingTo("1180");
        assertThat(enrichi.require("tva")).isEqualByComparingTo("180");
        assertThat(enrichi.require("net")).isEqualByComparingTo("1000");
    }
}
