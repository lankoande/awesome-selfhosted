package io.corebanking.fee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.schema.AccountingSchema;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.SchemaValidator;
import io.corebanking.schema.TemplateLine;
import io.corebanking.schema.expr.EvaluationContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeSchemaTest {

    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PRODUIT = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TAXE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private final AccountResolver resolveur = reference -> switch (reference.kind()) {
        case CONTRACT -> CLIENT;
        case PARAMETER -> switch (reference.value()) {
            case FeeSchemas.ROLE_INCOME -> PRODUIT;
            case FeeSchemas.ROLE_TAX -> TAXE;
            default -> throw new AccountResolver.UnresolvableAccountException(reference, "inconnu");
        };
        default -> throw new AccountResolver.UnresolvableAccountException(reference, "non gere");
    };

    @Test
    @DisplayName("le schema standard reste equilibre sur trois cents jeux de valeurs, arrondis compris")
    void schemaStandardEquilibre() {
        SchemaValidator.validate(FeeSchemas.standard(Currencies.XOF), Currencies.XOF);
        SchemaValidator.validate(FeeSchemas.standard(Currencies.EUR), Currencies.EUR);
        SchemaValidator.validate(FeeSchemas.standard(Currencies.TND), Currencies.TND);
    }

    @Test
    @DisplayName("la variante naive — debiter net + taxe et arrondir chaque ligne — est refusee")
    void varianteNaiveRefusee() {
        // C'est l'ecriture que l'on ecrit spontanement, et elle est fausse en XOF : le debit et la
        // somme des credits n'ont pas le meme arrondi des que les composantes ont des decimales.
        EventTemplate naif = EventTemplate.of(FeeSchemas.EVENT_FEE_CHARGE)
            .derive("total", "net + tax")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "net", "Produit"))
            .line(TemplateLine.credit("PARAM:fee_tax", "tax", "Taxe").onlyIf("tax > 0"))
            .build();

        assertThatThrownBy(() ->
            SchemaValidator.validate(naif, Currencies.XOF, "FEE_NAIF"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class)
            .hasMessageContaining("desequilibre");
    }

    @Test
    @DisplayName("sans taxe, la ligne de taxe n'est pas produite : l'ecriture compte deux lignes")
    void sansTaxe() {
        List<PostingLine> lignes = SchemaEngine.linesFor(
            FeeSchemas.feeCharge(Currencies.XOF),
            EvaluationContext.builder()
                .put("net", Money.of("2000", Currencies.XOF))
                .put("tax", Money.of("0", Currencies.XOF)).build(),
            resolveur, Currencies.XOF, java.time.LocalDate.of(2026, 9, 30));

        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).accountId()).isEqualTo(CLIENT);
        assertThat(lignes.get(1).accountId()).isEqualTo(PRODUIT);
    }

    @Test
    @DisplayName("avec taxe, trois lignes : le client est debite de la somme exacte des deux credits")
    void avecTaxe() {
        List<PostingLine> lignes = SchemaEngine.linesFor(
            FeeSchemas.feeCharge(Currencies.XOF),
            EvaluationContext.builder()
                .put("net", Money.of("1525", Currencies.XOF))
                .put("tax", Money.of("274", Currencies.XOF)).build(),
            resolveur, Currencies.XOF, java.time.LocalDate.of(2026, 9, 30));

        assertThat(lignes).hasSize(3);
        assertThat(lignes.get(0).amount()).isEqualTo(Money.of("1799", Currencies.XOF));
        assertThat(lignes.get(1).amount()).isEqualTo(Money.of("1525", Currencies.XOF));
        assertThat(lignes.get(2).amount()).isEqualTo(Money.of("274", Currencies.XOF));
    }

    @Test
    @DisplayName("le schema standard se decrit comme n'importe quel schema parametre")
    void schemaEnregistrable() {
        AccountingSchema schema = FeeSchemas.standard(Currencies.XOF);
        assertThat(schema.code()).isEqualTo(FeeSchemas.STANDARD_CODE);
        assertThat(schema.requireTemplate(FeeSchemas.EVENT_FEE_CHARGE).freeVariables())
            .containsExactlyInAnyOrder("net", "tax");
    }
}
