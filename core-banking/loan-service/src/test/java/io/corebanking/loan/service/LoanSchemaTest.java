package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.util.List;
import java.util.UUID;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaValidator;
import io.corebanking.schema.TemplateLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanSchemaTest {

    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final AccountResolver resolveur = reference -> COMPTE;


    @Test
    @DisplayName("les trois schemas du credit restent equilibres apres arrondi, dans trois devises")
    void schemasEquilibres() {
        SchemaValidator.validate(LoanSchemas.standard(Currencies.XOF), Currencies.XOF);
        SchemaValidator.validate(LoanSchemas.standard(Currencies.EUR), Currencies.EUR);
        SchemaValidator.validate(LoanSchemas.standard(Currencies.TND), Currencies.TND);
    }

    @Test
    @DisplayName("le deblocage reste equilibre meme si les frais depassent le capital")
    void fraisPlafonnesDansLeSchema() {
        // Le cas est deja refuse en amont — l'emprunteur ne recevrait rien — mais un schema
        // comptable ne doit pas pouvoir produire une ecriture desequilibree, meme appele avec des
        // valeurs qu'aucun chemin du code ne lui donne. Le controle par tirage l'a trouve.
        List<PostingLine> lignes = SchemaEngine.linesFor(
            LoanSchemas.disbursement(Currencies.XOF),
            EvaluationContext.builder()
                .put("principal", Money.of("100000", Currencies.XOF))
                .put("upfront_fees", Money.of("150000", Currencies.XOF)).build(),
            resolveur, Currencies.XOF, java.time.LocalDate.of(2026, 9, 15));

        assertThat(lignes).hasSize(2);
        assertThat(lignes.get(0).amount()).isEqualTo(Money.of("100000", Currencies.XOF));
        assertThat(lignes.get(1).amount()).isEqualTo(Money.of("100000", Currencies.XOF));
    }

    @Test
    @DisplayName("dotation et reprise sont deux evenements, jamais un seul a montant signe")
    void dotationEtRepriseSeparees() {
        // Le moteur de schemas refuse les montants negatifs — le sens est porte par la direction —
        // et la separation rend surtout les deux flux lisibles au compte de resultat, ou ils ne se
        // compensent pas.
        assertThat(LoanSchemas.provisionCharge(Currencies.XOF).freeVariables())
            .containsExactly("amount");
        assertThat(LoanSchemas.provisionRelease(Currencies.XOF).freeVariables())
            .containsExactly("amount");
        assertThat(LoanSchemas.standard(Currencies.XOF).templates()).hasSize(8);
    }

    @Test
    @DisplayName("l'exigibilite ne demande que les charges : le capital ne produit aucun flux")
    void variablesDeLExigibilite() {
        assertThat(LoanSchemas.instalmentDue(Currencies.XOF).freeVariables())
            .containsExactlyInAnyOrder("interest", "insurance", "fee", "tax");
    }

    @Test
    @DisplayName("la variante qui debite la somme non arrondie des charges est refusee")
    void varianteNaiveRefusee() {
        // Quatre composantes arrondies separement et un debit arrondi une seule fois : l'ecart
        // atteint deux unites des que trois d'entre elles ont des decimales. En XOF, cela fait
        // deux francs par echeance et par credit.
        EventTemplate naif = EventTemplate.of(LoanSchemas.EVENT_INSTALMENT_DUE)
            .derive("charges", "interest + insurance + fee + tax")
            .line(TemplateLine.debit("PARAM:accrued_receivable", "charges", "Echeance"))
            .line(TemplateLine.credit("PARAM:interest_income", "interest", "Interets")
                      .onlyIf("interest > 0"))
            .line(TemplateLine.credit("PARAM:insurance_income", "insurance", "Assurance")
                      .onlyIf("insurance > 0"))
            .line(TemplateLine.credit("PARAM:fee_income", "fee", "Frais").onlyIf("fee > 0"))
            .line(TemplateLine.credit("PARAM:tax_payable", "tax", "Taxe").onlyIf("tax > 0"))
            .build();

        assertThatThrownBy(() -> SchemaValidator.validate(naif, Currencies.XOF, "LOAN_NAIF"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class)
            .hasMessageContaining("desequilibre");
    }
}
