package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Form 1099-NEC reporting thresholds, loaded from a data file with its source noted.
 *
 * <p>Tax years without a published amount are deliberately absent: the report then says it doesn't know
 * instead of guessing (see CLAUDE.md — never invent tax figures).
 */
@Component
public class Form1099Thresholds {

    private final Map<Integer, String> amountsByYear = new LinkedHashMap<>();
    private final String source;

    Form1099Thresholds(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("tax-rules/form-1099-nec-thresholds.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            this.source = root.get("source").asText();
            for (JsonNode node : root.get("thresholds")) {
                amountsByYear.put(node.get("taxYear").asInt(), node.get("amount").asText());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load 1099 thresholds", e);
        }
    }

    public Optional<Money> forYear(int taxYear, String currency) {
        return Optional.ofNullable(amountsByYear.get(taxYear)).map(amount -> Money.of(amount, currency));
    }

    public String source() {
        return source;
    }
}
