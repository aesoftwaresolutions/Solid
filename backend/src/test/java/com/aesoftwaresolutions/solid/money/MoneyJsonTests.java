package com.aesoftwaresolutions.solid.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

/** Spec 002, AC 7. */
class MoneyJsonTests {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new MoneyJsonModule());

    record Invoice(Money total) {
    }

    @Test
    void serializesAmountAsString() throws Exception {
        String json = mapper.writeValueAsString(new Invoice(Money.of("1234.50", "USD")));
        assertThat(json).isEqualTo("{\"total\":{\"amount\":\"1234.50\",\"currency\":\"USD\"}}");
    }

    @Test
    void roundTrips() throws Exception {
        Invoice original = new Invoice(Money.of("-0.07", "USD"));
        Invoice parsed = mapper.readValue(mapper.writeValueAsString(original), Invoice.class);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void rejectsNumericAmount() {
        assertThatThrownBy(() -> mapper.readValue("{\"total\":{\"amount\":12.34,\"currency\":\"USD\"}}", Invoice.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("must be a string");
    }

    @Test
    void rejectsTooManyDecimals() {
        assertThatThrownBy(() -> mapper.readValue("{\"total\":{\"amount\":\"1.001\",\"currency\":\"USD\"}}", Invoice.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void rejectsMissingCurrency() {
        assertThatThrownBy(() -> mapper.readValue("{\"total\":{\"amount\":\"1.00\"}}", Invoice.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
