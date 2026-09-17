package com.aesoftwaresolutions.solid.money;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * JSON format for {@link Money}: {@code {"amount": "12.34", "currency": "USD"}}.
 * The amount must be a string so no client ever parses money as a floating-point number.
 * Registered automatically by Spring Boot because it is a Jackson {@code Module} bean.
 */
@Component
public class MoneyJsonModule extends SimpleModule {

    public MoneyJsonModule() {
        super("SolidMoney");
        addSerializer(Money.class, new Serializer());
        addDeserializer(Money.class, new Deserializer());
    }

    static class Serializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("amount", value.toDecimalString());
            gen.writeStringField("currency", value.currency());
            gen.writeEndObject();
        }
    }

    static class Deserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            JsonNode node = parser.readValueAsTree();
            JsonNode amount = node.get("amount");
            JsonNode currency = node.get("currency");
            if (amount == null || currency == null || !currency.isTextual()) {
                return ctxt.reportInputMismatch(Money.class, "Money requires string fields 'amount' and 'currency'");
            }
            if (!amount.isTextual()) {
                return ctxt.reportInputMismatch(Money.class,
                        "Money 'amount' must be a string like \"12.34\", not a JSON number");
            }
            try {
                return Money.of(amount.asText(), currency.asText());
            } catch (IllegalArgumentException | ArithmeticException e) {
                return ctxt.reportInputMismatch(Money.class, e.getMessage());
            }
        }

        @Override
        public Money getNullValue(DeserializationContext ctxt) {
            return null;
        }
    }

}
