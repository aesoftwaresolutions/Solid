package com.aesoftwaresolutions.solid.deductions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Published deduction rates, loaded from data files that carry their own source notes.
 * A tax year with no published figure is absent, and callers must report "unknown" rather than guess.
 */
@Component
public class DeductionRates {

    public record HomeOfficeSimplified(BigDecimal ratePerSquareFoot, int maximumSquareFeet, String source) {
    }

    private final Map<Integer, BigDecimal> mileageRates = new LinkedHashMap<>();
    private final String mileageSource;
    private final HomeOfficeSimplified homeOffice;

    DeductionRates(ObjectMapper mapper) {
        JsonNode mileage = read(mapper, "tax-rules/standard-mileage-rates.json");
        this.mileageSource = mileage.get("source").asText();
        for (JsonNode rate : mileage.get("rates")) {
            mileageRates.put(rate.get("taxYear").asInt(), new BigDecimal(rate.get("ratePerMile").asText()));
        }
        JsonNode home = read(mapper, "tax-rules/home-office-simplified.json");
        this.homeOffice = new HomeOfficeSimplified(new BigDecimal(home.get("ratePerSquareFoot").asText()),
                home.get("maximumSquareFeet").asInt(), home.get("source").asText());
    }

    public Optional<BigDecimal> mileageRate(int taxYear) {
        return Optional.ofNullable(mileageRates.get(taxYear));
    }

    public String mileageSource() {
        return mileageSource;
    }

    public HomeOfficeSimplified homeOfficeSimplified() {
        return homeOffice;
    }

    private static JsonNode read(ObjectMapper mapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + path, e);
        }
    }
}
