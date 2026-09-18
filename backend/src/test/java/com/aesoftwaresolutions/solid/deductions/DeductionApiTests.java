package com.aesoftwaresolutions.solid.deductions;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 015. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DeductionApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    String vehicle;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        vehicle = api.post(base + "/vehicles", Map.of("name", "2019 Transit Van"), HttpStatus.CREATED).get("id").asText();
    }

    private JsonNode trip(String date, String miles, String category, String purpose, HttpStatus expected) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", vehicle);
        body.put("tripDate", date);
        body.put("miles", miles);
        body.put("category", category);
        if (purpose != null) {
            body.put("purpose", purpose);
        }
        return api.post(base + "/mileage-trips", body, expected);
    }

    @Test
    void ac1_validatesMilesAndBusinessPurpose() {
        trip("2025-03-01", "12.4", "business", "Client site visit", HttpStatus.CREATED);
        assertThat(trip("2025-03-02", "8.0", "business", null, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("PURPOSE_REQUIRED");
        trip("2025-03-02", "8.0", "commuting", null, HttpStatus.CREATED);
        trip("2025-03-03", "0", "business", "Zero", HttpStatus.BAD_REQUEST);
        trip("2025-03-03", "12.45", "business", "Too precise", HttpStatus.BAD_REQUEST);
        trip("2025-03-03", "20000", "business", "Too far", HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac2_mileageReportUsesThePublishedRate() {
        trip("2025-01-10", "600.0", "business", "Deliveries", HttpStatus.CREATED);
        trip("2025-02-10", "400.0", "business", "Client visits", HttpStatus.CREATED);
        trip("2025-02-11", "30.0", "commuting", null, HttpStatus.CREATED);
        trip("2025-02-12", "15.5", "personal", null, HttpStatus.CREATED);
        trip("2024-12-31", "999.0", "business", "Last year", HttpStatus.CREATED);

        JsonNode report = api.get(base + "/reports/mileage?taxYear=2025");
        assertThat(report.get("rateKnown").asBoolean()).isTrue();
        assertThat(report.get("ratePerMile").decimalValue()).isEqualByComparingTo("0.70");
        assertThat(report.get("businessMiles").asDouble()).isEqualTo(1000.0);
        assertThat(report.get("commutingMiles").asDouble()).isEqualTo(30.0);
        assertThat(report.get("personalMiles").asDouble()).isEqualTo(15.5);
        assertThat(report.get("estimatedDeduction").get("amount").asText()).isEqualTo("700.00");
        assertThat(report.get("byVehicle").get(0).get("businessMiles").asDouble()).isEqualTo(1000.0);
        assertThat(report.get("source").asText()).contains("Publication 463");
    }

    @Test
    void ac3_unknownYearReportsMilesWithoutGuessing() {
        trip("2026-01-05", "120.0", "business", "Trade show", HttpStatus.CREATED);
        JsonNode report = api.get(base + "/reports/mileage?taxYear=2026");
        assertThat(report.get("rateKnown").asBoolean()).isFalse();
        assertThat(report.get("ratePerMile").isNull()).isTrue();
        assertThat(report.get("estimatedDeduction").isNull()).isTrue();
        assertThat(report.get("businessMiles").asDouble()).isEqualTo(120.0);
        assertThat(report.get("note").asText()).contains("will not guess");
    }

    @Test
    void ac4_ac5_homeOfficeSimplifiedIsCappedAndProrated() {
        JsonNode saved = api.put(base + "/home-office?taxYear=2026",
                Map.of("method", "simplified", "totalHomeSquareFeet", 2000, "officeSquareFeet", 400));
        assertThat(saved.get("officeSquareFeet").asInt()).isEqualTo(400);

        JsonNode report = api.get(base + "/reports/home-office?taxYear=2026");
        assertThat(report.get("countedSquareFeet").asInt()).isEqualTo(300);
        assertThat(report.get("deduction").get("amount").asText()).isEqualTo("1500.00");
        assertThat(report.get("businessUsePercent").asDouble()).isEqualTo(20.0);
        assertThat(report.get("source").asText()).contains("Publication 587");

        api.put(base + "/home-office?taxYear=2026",
                Map.of("method", "simplified", "totalHomeSquareFeet", 2000, "officeSquareFeet", 120, "monthsUsed", 6));
        JsonNode prorated = api.get(base + "/reports/home-office?taxYear=2026");
        assertThat(prorated.get("deduction").get("amount").asText()).isEqualTo("300.00"); // 120 x 5 x 6/12

        api.get(base + "/reports/home-office?taxYear=2025", HttpStatus.NOT_FOUND);
    }

    @Test
    void ac4_ac6_validatesSquareFeetAndRefusesActualMethod() {
        api.put2(base + "/home-office?taxYear=2026",
                Map.of("method", "simplified", "totalHomeSquareFeet", 500, "officeSquareFeet", 600),
                HttpStatus.BAD_REQUEST);
        JsonNode actual = api.put2(base + "/home-office?taxYear=2026",
                Map.of("method", "actual", "totalHomeSquareFeet", 2000, "officeSquareFeet", 200),
                HttpStatus.BAD_REQUEST);
        assertThat(actual.get("detail").asText()).contains("tax rule pack");
    }

    @Test
    void ac7_viewersCanReadButNotWrite() {
        trip("2025-03-01", "10.0", "business", "Client", HttpStatus.CREATED);
        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get(base + "/reports/mileage?taxYear=2025", HttpStatus.OK);
        viewer.post(base + "/mileage-trips", Map.of("vehicleId", vehicle, "tripDate", "2025-04-01", "miles", "5.0",
                "category", "business", "purpose", "Nope"), HttpStatus.FORBIDDEN);
    }
}
