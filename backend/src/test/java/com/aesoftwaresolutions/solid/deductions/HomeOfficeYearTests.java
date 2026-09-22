package com.aesoftwaresolutions.solid.deductions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * Spec 050, finding 2 — the simplified home-office rate was applied to every year, including ones before the
 * method existed. A figure with no published rate behind it is exactly what CLAUDE.md forbids.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HomeOfficeYearTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
    }

    private JsonNode declareAndReport(int taxYear) {
        api.put(base + "/home-office?taxYear=" + taxYear, Map.of("method", "simplified",
                "totalHomeSquareFeet", 1000, "officeSquareFeet", 200));
        return api.get(base + "/reports/home-office?taxYear=" + taxYear);
    }

    @Test
    void aYearBeforeTheSimplifiedMethodExistedGetsNoDeduction() {
        JsonNode report = declareAndReport(2011);

        assertThat(report.get("deduction").isNull()).as("no rate for 2011, so no figure").isTrue();
        assertThat(report.get("note").asText()).contains("No published simplified-method rate for 2011");
        // The declaration itself is still reported: the facts are the person's, only the figure is missing.
        assertThat(report.get("officeSquareFeet").asInt()).isEqualTo(200);
    }

    @Test
    void aYearTheRateCoversStillWorksOutTheDeduction() {
        JsonNode report = declareAndReport(2024);

        assertThat(report.get("deduction").get("amount").asText()).isEqualTo("1000.00");
        assertThat(report.get("source").asText()).contains("Rev. Proc. 2013-13");
    }
}
