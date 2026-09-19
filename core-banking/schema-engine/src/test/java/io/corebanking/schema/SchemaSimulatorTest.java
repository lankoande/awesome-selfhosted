package io.corebanking.schema;

import static io.corebanking.kernel.money.Currencies.EUR;
import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'essai d'un schema : ce que la redaction ne peut pas deviner en lisant ses propres expressions.
 */
class SchemaSimulatorTest {

    /** Commission saine : net et taxe arrondis chacun pour soi, total egal a leur somme. */
    private EventTemplate commission() {
        return EventTemplate.of("FEE_CHARGE")
            .derive("net_booked", "round(net, 0)")
            .derive("tax_booked", "round(tax, 0)")
            .derive("total", "net_booked + tax_booked")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "net_booked", "Produit"))
            .line(TemplateLine.credit("PARAM:fee_tax", "tax_booked", "Taxe")
                      .onlyIf("tax_booked > 0"))
            .build();
    }

    private Map<String, BigDecimal> values(String net, String tax) {
        return Map.of("net", new BigDecimal(net), "tax", new BigDecimal(tax));
    }

    @Test
    @DisplayName("l'essai rend les variables derivees, les lignes retenues et les totaux")
    void produces_the_entry() {
        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(commission(), XOF, values("1234.56", "222.22"));

        assertThat(outcome.rejection()).isNull();
        assertThat(outcome.variables()).containsExactlyInAnyOrder("net", "tax");
        assertThat(outcome.derived()).extracting(SchemaSimulator.Derived::name)
            .containsExactly("net_booked", "tax_booked", "total");
        assertThat(outcome.derived().get(2).value()).isEqualByComparingTo("1457");
        assertThat(outcome.lines()).filteredOn(SchemaSimulator.Line::posted).hasSize(3);
        assertThat(outcome.debit()).isEqualByComparingTo("1457");
        assertThat(outcome.credit()).isEqualByComparingTo("1457");
        assertThat(outcome.imbalance()).isEqualByComparingTo("0");
    }

    /**
     * Une ligne ecartee doit dire pourquoi. Sans la raison, celui qui redige cherche une faute
     * dans l'expression du montant alors que c'est la condition qui l'a mise de cote.
     */
    @Test
    @DisplayName("une ligne ecartee porte sa raison : condition fausse, ou montant nul")
    void says_why_a_line_is_dropped() {
        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(commission(), XOF, values("1000", "0"));

        assertThat(outcome.rejection()).isNull();
        SchemaSimulator.Line taxe = outcome.lines().get(2);
        assertThat(taxe.posted()).isFalse();
        assertThat(taxe.skipped()).isEqualTo("CONDITION");
        assertThat(outcome.debit()).isEqualByComparingTo("1000");
    }

    /**
     * Le defaut d'arrondi que la validation par tirage refuse : l'essai doit le montrer sur le cas
     * precis, sinon le contre-exemple reste un jeu de chiffres sans ecriture.
     */
    @Test
    @DisplayName("un desequilibre d'arrondi se voit sur le cas, avec les deux totaux")
    void shows_the_rounding_gap() {
        EventTemplate naif = EventTemplate.of("FEE_CHARGE")
            .derive("total", "net + tax")
            .line(TemplateLine.debit("CONTRACT", "round(total, 0)", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "round(net, 0)", "Produit"))
            .line(TemplateLine.credit("PARAM:fee_tax", "round(tax, 0)", "Taxe"))
            .build();

        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(naif, XOF, values("0.5", "0.5"));

        assertThat(outcome.rejection()).isNotNull();
        assertThat(outcome.rejection().code()).isEqualTo("LIGNE_UNIQUE");
        assertThat(outcome.lines()).filteredOn(line -> "MONTANT_NUL".equals(line.skipped()))
            .hasSize(2);
    }

    /**
     * Le moteur refuse un montant non comptabilisable plutot que de l'arrondir. L'essai doit
     * refuser de la meme facon : un essai qui arrondirait montrerait une ecriture que la
     * production n'accepterait pas.
     */
    @Test
    @DisplayName("un montant non comptabilisable est refuse, pas arrondi en silence")
    void refuses_an_unbookable_amount() {
        EventTemplate brut = EventTemplate.of("FEE_CHARGE")
            .derive("total", "net")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "total", "Produit"))
            .build();

        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(brut, XOF, Map.of("net", new BigDecimal("1234.56")));

        assertThat(outcome.rejection()).isNotNull();
        assertThat(outcome.rejection().code()).isEqualTo("MONTANT_NON_COMPTABILISABLE");
        assertThat(outcome.rejection().detail()).contains("round(");
    }

    @Test
    @DisplayName("un montant negatif est refuse : le sens est porte par la direction")
    void refuses_a_negative_amount() {
        EventTemplate inverse = EventTemplate.of("FEE_CHARGE")
            .derive("total", "0 - net")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "total", "Produit"))
            .build();

        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(inverse, XOF, Map.of("net", new BigDecimal("100")));

        assertThat(outcome.rejection().code()).isEqualTo("MONTANT_NEGATIF");
    }

    /**
     * La devise change l'echelle, donc le resultat : le meme schema et le meme cas ne rendent pas
     * la meme ecriture en XOF et en EUR. Un essai qui ignorerait la devise mentirait la moitie
     * du temps.
     */
    @Test
    @DisplayName("l'echelle de la devise change l'ecriture")
    void currency_scale_changes_the_entry() {
        EventTemplate deuxDecimales = EventTemplate.of("FEE_CHARGE")
            .derive("net_booked", "round(net, 2)")
            .derive("tax_booked", "round(tax, 2)")
            .derive("total", "net_booked + tax_booked")
            .line(TemplateLine.debit("CONTRACT", "total", "Commission"))
            .line(TemplateLine.credit("PARAM:fee_income", "net_booked", "Produit"))
            .line(TemplateLine.credit("PARAM:fee_tax", "tax_booked", "Taxe"))
            .build();

        assertThat(SchemaSimulator.run(deuxDecimales, EUR, values("10.125", "1.005")).rejection())
            .isNull();
        // Les memes montants, en XOF, ne sont pas comptabilisables : le franc n'a pas de centime.
        assertThat(SchemaSimulator.run(deuxDecimales, XOF, values("10.125", "1.005")).rejection())
            .extracting(SchemaSimulator.Rejection::code).isEqualTo("MONTANT_NON_COMPTABILISABLE");
    }

    /** Une variable non fournie vaut zero : l'essai se lance sur une saisie partielle. */
    @Test
    @DisplayName("une variable absente vaut zero, l'essai ne tombe pas")
    void missing_variable_defaults_to_zero() {
        SchemaSimulator.Outcome outcome =
            SchemaSimulator.run(commission(), XOF, Map.of("net", new BigDecimal("500")));

        assertThat(outcome.debit()).isEqualByComparingTo("500");
        assertThat(outcome.rejection()).isNull();
    }
}
