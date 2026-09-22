package com.aesoftwaresolutions.solid.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 049. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaxFigureApiTests {

    private static final String SOURCE =
            "IRS Notice 2099-01, standard mileage rates for 2099, as printed on irs.gov on 1 January 2099.";
    /** Far enough out that no file in the jar has a figure for it. */
    private static final int FUTURE_YEAR = 2099;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient admin;
    ApiClient user;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        user = new ApiClient(rest);
        jdbc.update("update iam.user_account set is_instance_admin = true where email = ?", user.email());
        admin = new ApiClient(rest, false).loginExisting(user.email(), ApiClient.PASSWORD, user.mfaSecret());
        // Clear anything a previous test in this class left behind: these figures are instance-wide.
        jdbc.update("delete from tax.figure");

        String org = admin.newOrg();
        String entity = admin.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        admin.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private JsonNode addFigure(String key, int year, String value, boolean supersede, HttpStatus expected) {
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("taxYear", year);
        body.put("value", value);
        body.put("source", SOURCE);
        body.put("supersede", supersede);
        return admin.post("/api/v1/instance/tax-figures", body, expected);
    }

    private JsonNode mileageReport(int year) {
        return admin.get(base + "/reports/mileage?taxYear=" + year);
    }

    private void logATrip(int year) {
        String vehicleId = admin.post(base + "/vehicles",
                Map.of("name", "Van", "inServiceDate", year + "-01-01"), HttpStatus.CREATED)
                .get("id").asText();
        admin.post(base + "/mileage-trips", Map.of("vehicleId", vehicleId, "tripDate", year + "-03-01",
                        "miles", "100", "category", "business", "purpose", "Client visit"),
                HttpStatus.CREATED);
    }

    @Test
    void ac1_ac2_aRateAddedHereIsUsedAndUntilThenTheYearIsUnknown() {
        logATrip(FUTURE_YEAR);

        JsonNode before = mileageReport(FUTURE_YEAR);
        assertThat(before.get("rateKnown").asBoolean()).as("no rate on file, so nothing is invented").isFalse();
        assertThat(before.get("note").asText()).containsIgnoringCase("rate");

        addFigure("mileage_rate_per_mile", FUTURE_YEAR, "0.80", false, HttpStatus.OK);

        JsonNode after = mileageReport(FUTURE_YEAR);
        assertThat(after.get("rateKnown").asBoolean()).isTrue();
        assertThat(after.get("estimatedDeduction").get("amount").asText()).isEqualTo("80.00");
        assertThat(after.get("source").asText()).contains("Added on this installation").contains("Notice 2099-01");
    }

    @Test
    void ac3_aFigureAddedHereBeatsTheOneInTheJar() {
        int knownYear = 2025;   // the shipped file has a rate for this year
        logATrip(knownYear);
        assertThat(mileageReport(knownYear).get("estimatedDeduction").get("amount").asText()).isEqualTo("70.00");

        addFigure("mileage_rate_per_mile", knownYear, "0.99", false, HttpStatus.OK);

        assertThat(mileageReport(knownYear).get("estimatedDeduction").get("amount").asText()).isEqualTo("99.00");
    }

    @Test
    void ac4_ac7_supersedingKeepsTheOldRowAndTheTrailRecordsBoth() {
        addFigure("mileage_rate_per_mile", FUTURE_YEAR, "0.80", false, HttpStatus.OK);
        JsonNode refused = addFigure("mileage_rate_per_mile", FUTURE_YEAR, "0.85", false, HttpStatus.CONFLICT);
        assertThat(refused.toString()).contains("FIGURE_EXISTS").contains("supersede");

        addFigure("mileage_rate_per_mile", FUTURE_YEAR, "0.85", true, HttpStatus.OK);

        List<JsonNode> figures = java.util.stream.StreamSupport
                .stream(admin.get("/api/v1/instance/tax-figures").spliterator(), false)
                .filter(f -> f.get("taxYear").asInt() == FUTURE_YEAR).toList();
        assertThat(figures).hasSize(2);
        assertThat(figures.stream().filter(f -> f.get("inUse").asBoolean()).count()).isEqualTo(1);
        assertThat(figures.stream().filter(f -> f.get("inUse").asBoolean()).findFirst().orElseThrow()
                .get("value").asText()).isEqualTo("0.85");

        String audit = jdbc.queryForList("select action, details::text from audit.event order by seq desc limit 30")
                .toString();
        assertThat(audit).contains("tax_figure_added").contains("tax_figure_superseded").contains("Notice 2099-01");
    }

    @Test
    void ac5_nonsenseIsRefusedWithAReasonRatherThanStored() {
        assertThat(addFigure("mileage_rate_per_gallon", FUTURE_YEAR, "0.80", false, HttpStatus.BAD_REQUEST)
                .toString()).contains("UNKNOWN_FIGURE");
        assertThat(addFigure("mileage_rate_per_mile", 1800, "0.80", false, HttpStatus.BAD_REQUEST)
                .toString()).contains("BAD_TAX_YEAR");
        assertThat(addFigure("mileage_rate_per_mile", FUTURE_YEAR, "not a number", false, HttpStatus.BAD_REQUEST)
                .toString()).contains("BAD_VALUE");
        assertThat(addFigure("mileage_rate_per_mile", FUTURE_YEAR, "0.8055", false, HttpStatus.BAD_REQUEST)
                .toString()).contains("decimal place");

        Map<String, Object> thin = new HashMap<>();
        thin.put("key", "mileage_rate_per_mile");
        thin.put("taxYear", FUTURE_YEAR);
        thin.put("value", "0.80");
        thin.put("source", "IRS said so");
        assertThat(admin.post("/api/v1/instance/tax-figures", thin, HttpStatus.BAD_REQUEST).toString())
                .contains("SOURCE_REQUIRED");

        assertThat(admin.get("/api/v1/instance/tax-figures")).isEmpty();
    }

    @Test
    void ac6_onlyAnInstanceAdministratorTouchesThese() {
        ApiClient ordinary = new ApiClient(rest);
        ordinary.get("/api/v1/instance/tax-figures", HttpStatus.FORBIDDEN);
        ordinary.post("/api/v1/instance/tax-figures",
                Map.of("key", "mileage_rate_per_mile", "taxYear", FUTURE_YEAR, "value", "0.80", "source", SOURCE),
                HttpStatus.FORBIDDEN);
        new ApiClient(rest, false).get("/api/v1/instance/tax-figures", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theKeysThisVersionUnderstandsAreListedWithTheirUnits() {
        JsonNode keys = admin.get("/api/v1/instance/tax-figures/keys");

        assertThat(keys.toString()).contains("mileage_rate_per_mile").contains("US dollars per mile")
                .contains("form_1099_nec_threshold");
    }
}
