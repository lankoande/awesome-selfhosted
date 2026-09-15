package io.corebanking.api.web;

import io.corebanking.kernel.money.Money;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Un montant sort en chaine, avec sa devise : {@code {"amount":"20000","currency":"XOF"}}. Jamais
 * en nombre flottant — un client JavaScript qui lit 0.1 + 0.2 n'est pas un client de banque.
 */
@JacksonComponent
public class MoneySerializer extends ValueSerializer<Money> {

    @Override
    public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt)
            throws JacksonException {
        gen.writeStartObject();
        gen.writeStringProperty("amount", value.roundToCurrency().amount().toPlainString());
        gen.writeStringProperty("currency", value.currency().code());
        gen.writeEndObject();
    }
}
