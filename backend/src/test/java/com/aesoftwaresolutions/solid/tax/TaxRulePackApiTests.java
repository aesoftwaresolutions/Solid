package com.aesoftwaresolutions.solid.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.billing.Form1099Thresholds;
import com.aesoftwaresolutions.solid.deductions.DeductionRates;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 021, AC 1, 2, 6 and 7 against the rule files this repository actually ships. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaxRulePackApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    TaxRulePacks packs;

    @Autowired
    Form1099Thresholds thresholds;

    @Autowired
    DeductionRates rates;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
    }

    private static List<String> ids(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.get("id").asText()));
        return result;
    }

    @Test
    void ac1_everyRuleFileIsListedWithItsSource() {
        JsonNode listed = api.get("/api/v1/tax/rule-packs");

        assertThat(ids(listed)).contains("form-1099-nec-thresholds", "standard-mileage-rates",
                "home-office-simplified");
        listed.forEach(pack -> assertThat(pack.get("source").asText()).isNotBlank());
        assertThat(listed).hasSize(packs.all().size());
    }

    @Test
    void ac2_ac7_coverageMatchesWhatTheFilesActuallyHold() {
        // The registry must agree with the readers that use the same files — one source of truth.
        int knownMileageYear = rates.mileageRate(2025).isPresent() ? 2025 : -1;
        assertThat(knownMileageYear).as("the shipped mileage file lists 2025").isEqualTo(2025);

        JsonNode covered = api.get("/api/v1/tax/rule-coverage?taxYear=2025");
        JsonNode mileage = pack(covered, "standard-mileage-rates");
        assertThat(mileage.get("covered").asBoolean()).isTrue();
        assertThat(mileage.get("reason").asText()).contains("2025");

        JsonNode farFuture = api.get("/api/v1/tax/rule-coverage?taxYear=2099");
        JsonNode futureMileage = pack(farFuture, "standard-mileage-rates");
        assertThat(futureMileage.get("covered").asBoolean()).isFalse();
        assertThat(futureMileage.get("todos")).isNotEmpty();
        assertThat(farFuture.get("missing").asInt()).isPositive();
        assertThat(farFuture.get("note").asText()).contains("will not");

        assertThat(thresholds.forYear(2026, "USD")).isPresent();
        assertThat(pack(api.get("/api/v1/tax/rule-coverage?taxYear=2026"), "form-1099-nec-thresholds")
                .get("covered").asBoolean()).isTrue();
    }

    @Test
    void ac6_signingInIsRequired() {
        ApiClient anonymous = new ApiClient(rest, false);
        anonymous.get("/api/v1/tax/rule-packs", HttpStatus.UNAUTHORIZED);
        anonymous.get("/api/v1/tax/rule-coverage?taxYear=2026", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anImpossibleYearIsRefused() {
        api.get("/api/v1/tax/rule-coverage?taxYear=1800", HttpStatus.BAD_REQUEST);
    }

    private static JsonNode pack(JsonNode report, String id) {
        for (JsonNode node : report.get("packs")) {
            if (node.get("id").asText().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("No pack " + id + " in " + report);
    }
}
