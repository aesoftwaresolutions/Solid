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

    /** Spec 065 row 11: over the API an amount is a plain decimal, never scientific notation. */
    @Test
    void spec065_row11_rejectsScientificNotationAndOtherOddAmounts() throws Exception {
        for (String odd : new String[]{"1e3", "1E3", "1E+2", "5e-1", "0x10", "1_000", " 1.00", "Infinity", "NaN"}) {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"total\":{\"amount\":\"" + odd + "\",\"currency\":\"USD\"}}", Invoice.class))
                    .as(odd).isInstanceOf(com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        }
        for (String[] ok : new String[][]{{"1000", "1000.00"}, {"-12.50", "-12.50"}, {".5", "0.50"}, {"+3", "3.00"}}) {
            Invoice parsed = mapper.readValue(
                    "{\"total\":{\"amount\":\"" + ok[0] + "\",\"currency\":\"USD\"}}", Invoice.class);
            org.assertj.core.api.Assertions.assertThat(parsed.total().toDecimalString()).as(ok[0]).isEqualTo(ok[1]);
        }
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
