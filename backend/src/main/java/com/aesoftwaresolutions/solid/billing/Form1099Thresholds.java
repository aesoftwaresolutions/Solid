package com.aesoftwaresolutions.solid.billing;

import com.aesoftwaresolutions.solid.money.Money;
import com.aesoftwaresolutions.solid.tax.TaxFigures;
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
    private final TaxFigures figures;

    Form1099Thresholds(ObjectMapper mapper, TaxFigures figures) {
        this.figures = figures;
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

    /** A threshold supplied on this installation wins over the file in the jar (spec 049). */
    public Optional<Money> forYear(int taxYear, String currency) {
        return figures.inForce(TaxFigures.Key.form_1099_nec_threshold, taxYear)
                .map(figure -> Money.of(figure.value().toPlainString(), currency))
                .or(() -> Optional.ofNullable(amountsByYear.get(taxYear)).map(amount -> Money.of(amount, currency)));
    }

    public String source(int taxYear) {
        return figures.inForce(TaxFigures.Key.form_1099_nec_threshold, taxYear)
                .map(figure -> "Added on this installation: " + figure.source())
                .orElse(source);
    }
}
