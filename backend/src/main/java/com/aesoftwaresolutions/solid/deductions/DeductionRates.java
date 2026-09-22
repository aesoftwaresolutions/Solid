package com.aesoftwaresolutions.solid.deductions;

import com.aesoftwaresolutions.solid.tax.TaxFigures;
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
    private final TaxFigures figures;

    DeductionRates(ObjectMapper mapper, TaxFigures figures) {
        this.figures = figures;
        JsonNode mileage = read(mapper, "tax-rules/standard-mileage-rates.json");
        this.mileageSource = mileage.get("source").asText();
        for (JsonNode rate : mileage.get("rates")) {
            mileageRates.put(rate.get("taxYear").asInt(), new BigDecimal(rate.get("ratePerMile").asText()));
        }
        JsonNode home = read(mapper, "tax-rules/home-office-simplified.json");
        this.homeOffice = new HomeOfficeSimplified(new BigDecimal(home.get("ratePerSquareFoot").asText()),
                home.get("maximumSquareFeet").asInt(), home.get("source").asText());
    }

    /**
     * A rate someone supplied for this installation wins over the file in the jar (spec 049); a year neither
     * knows stays empty, and the caller reports "unknown" rather than guessing.
     */
    public Optional<BigDecimal> mileageRate(int taxYear) {
        return figures.inForce(TaxFigures.Key.mileage_rate_per_mile, taxYear).map(TaxFigures.Figure::value)
                .or(() -> Optional.ofNullable(mileageRates.get(taxYear)));
    }

    /** The source of whichever rate {@link #mileageRate} would return for that year. */
    public String mileageSource(int taxYear) {
        return figures.inForce(TaxFigures.Key.mileage_rate_per_mile, taxYear)
                .map(figure -> "Added on this installation: " + figure.source())
                .orElse(mileageSource);
    }

    public HomeOfficeSimplified homeOfficeSimplified(int taxYear) {
        return figures.inForce(TaxFigures.Key.home_office_rate_per_square_foot, taxYear)
                .map(figure -> new HomeOfficeSimplified(figure.value(), homeOffice.maximumSquareFeet(),
                        "Added on this installation: " + figure.source()))
                .orElse(homeOffice);
    }

    private static JsonNode read(ObjectMapper mapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + path, e);
        }
    }
}
