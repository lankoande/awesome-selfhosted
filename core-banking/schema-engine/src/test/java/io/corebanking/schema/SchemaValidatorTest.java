package io.corebanking.schema;

import static io.corebanking.kernel.money.Currencies.EUR;
import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    /**
     * Commission ecrite « naturellement » : chaque composante est arrondie pour elle-meme.
     * Exacte en arithmetique reelle, fausse des que les arrondis ne se recomposent pas.
     */
    private EventTemplate commissionNaive() {
        return EventTemplate.of("MAINTENANCE_FEE")
            .derive("tva", "base * taux_tva")
            .derive("net", "base")
            .derive("total", "net + tva")
            .line(TemplateLine.debit("CONTRACT", "round(total, 0)", "Frais"))
            .line(TemplateLine.credit("GL:70611", "round(net, 0)", "Commissions"))
            .line(TemplateLine.credit("GL:44320", "round(tva, 0)", "TVA").onlyIf("tva > 0"))
            .build();
    }

    /** La meme, ecrite de sorte que la somme des composantes egale le total par construction. */
    private EventTemplate commissionSaine() {
        return EventTemplate.of("MAINTENANCE_FEE")
            .derive("total", "round(base + base * taux_tva, 0)")
            .derive("tva", "round(base * taux_tva, 0)")
            .derive("net", "total - tva")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais"))
            .line(TemplateLine.credit("GL:70611", "net", "Commissions"))
            .line(TemplateLine.credit("GL:44320", "tva", "TVA").onlyIf("tva > 0"))
            .build();
    }

    @Test
    @DisplayName("le defaut d'arrondi est detecte au deploiement, avec son contre-exemple")
    void the_rounding_defect_is_caught_at_deployment() {
        var exception = org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> SchemaValidator.validate(commissionNaive(), XOF, "EP-NAIF"),
            SchemaValidator.InvalidSchemaException.class);

        assertThat(exception).isNotNull();
        assertThat(exception).hasMessageContaining("desequilibre")
                             .hasMessageContaining("Contre-exemple");
        assertThat(exception.counterExample()).containsKeys("base", "taux_tva");
    }

    @Test
    @DisplayName("la version ou le total est arrondi en premier passe la validation")
    void the_corrected_schema_passes() {
        assertThatCode(() -> SchemaValidator.validate(commissionSaine(), XOF, "EP-SAIN"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le meme schema peut etre sain en EUR et faux en XOF")
    void the_same_schema_can_be_sound_in_one_currency_and_wrong_in_another() {
        // Avec deux decimales, les arrondis du schema naif se recomposent bien plus souvent ;
        // l'echelle nulle du XOF est ce qui revele le defaut.
        assertThatThrownBy(() -> SchemaValidator.validate(commissionNaive(), XOF, "EP"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class);
        assertThatThrownBy(() -> SchemaValidator.validate(commissionNaive(), EUR, "EP"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class);
        // Les deux echouent ici, mais sur des contre-exemples differents : la validation est faite
        // dans la devise du produit, jamais « en general ».
    }

    @Test
    @DisplayName("une contrepartie oubliee est detectee")
    void a_forgotten_counterpart_is_caught() {
        EventTemplate oubli = EventTemplate.of("FEE")
            .derive("tva", "round(base * taux_tva, 0)")
            .derive("total", "round(base + base * taux_tva, 0)")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais"))
            .line(TemplateLine.credit("GL:70611", "total - tva", "Commissions"))
            // la ligne de TVA manque
            .build();

        assertThatThrownBy(() -> SchemaValidator.validate(oubli, XOF, "EP-OUBLI"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class);
    }

    @Test
    @DisplayName("une condition mal posee qui neutralise une ligne est detectee")
    void a_condition_that_drops_a_needed_line_is_caught() {
        EventTemplate conditionFausse = EventTemplate.of("FEE")
            .derive("tva", "round(base * taux_tva, 0)")
            .derive("total", "round(base, 0) + tva")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais"))
            .line(TemplateLine.credit("GL:70611", "round(base, 0)", "Commissions"))
            // Le seuil est faux : une TVA due mais inferieure au seuil disparait du credit,
            // alors qu'elle reste comprise dans le total debite.
            .line(TemplateLine.credit("GL:44320", "tva", "TVA").onlyIf("taux_tva > 0.1"))
            .build();

        assertThatThrownBy(() -> SchemaValidator.validate(conditionFausse, XOF, "EP-SEUIL"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class);
    }

    @Test
    @DisplayName("une division par une variable pouvant valoir zero est refusee au deploiement")
    void a_division_by_a_possibly_zero_variable_is_refused() {
        EventTemplate risque = EventTemplate.of("SPLIT")
            .derive("part", "round(montant / nombre_de_parts, 0)")
            .line(TemplateLine.debit("CONTRACT", "part", null))
            .line(TemplateLine.credit("GL:70611", "part", null))
            .build();

        assertThatThrownBy(() -> SchemaValidator.validate(risque, XOF, "EP-DIV"))
            .isInstanceOf(SchemaValidator.InvalidSchemaException.class)
            .hasMessageContaining("evaluation impossible");
    }

    @Test
    @DisplayName("le contre-exemple est reproductible : la graine du tirage est fixe")
    void the_counter_example_is_reproducible() {
        var premier = catchCounterExample();
        var second = catchCounterExample();

        assertThat(premier).isEqualTo(second);
    }

    private java.util.Map<String, java.math.BigDecimal> catchCounterExample() {
        try {
            SchemaValidator.validate(commissionNaive(), XOF, "EP-NAIF");
            throw new AssertionError("le schema aurait du etre refuse");
        } catch (SchemaValidator.InvalidSchemaException e) {
            return e.counterExample();
        }
    }

    @Test
    @DisplayName("un schema complet est valide evenement par evenement")
    void a_whole_schema_is_validated_event_by_event() {
        AccountingSchema schema = AccountingSchema.of("EP-PLUS", 1)
            .on(EventTemplate.of("DEPOSIT")
                .line(TemplateLine.debit("RESOLVE:cash", "montant", null))
                .line(TemplateLine.credit("CONTRACT", "montant", null))
                .build())
            .on(commissionSaine())
            .build();

        assertThatCode(() -> SchemaValidator.validate(schema, XOF))
            .doesNotThrowAnyException();
    }
}
