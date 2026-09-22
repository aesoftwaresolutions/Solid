package com.aesoftwaresolutions.solid.setup;

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

/** Spec 044. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SetupTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
    }

    private JsonNode setup() {
        return api.get(base + "/setup");
    }

    private static JsonNode step(JsonNode setup, String key) {
        return java.util.stream.StreamSupport.stream(setup.get("steps").spliterator(), false)
                .filter(s -> s.get("key").asText().equals(key)).findFirst().orElseThrow();
    }

    private static String status(JsonNode setup, String key) {
        return step(setup, key).get("status").asText();
    }

    private Map<String, String> applyTemplate(String entityId, String template) {
        Map<String, String> codes = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + entityId + "/accounts/apply-template",
                        Map.of("template", template), HttpStatus.CREATED)
                .forEach(a -> codes.put(a.get("code").asText(), a.get("id").asText()));
        return codes;
    }

    @Test
    void ac1_ac2_aNewEntityHasEverythingToDoAndTheListFollowsTheBooks() {
        JsonNode fresh = setup();

        assertThat(fresh.get("complete").asBoolean()).isFalse();
        assertThat(fresh.get("doneCount").asInt()).isZero();
        assertThat(status(fresh, "chart_of_accounts")).isEqualTo("todo");
        assertThat(status(fresh, "first_entry")).isEqualTo("todo");
        // An optional step is shown, not hidden, so nobody wonders whether they missed it.
        assertThat(status(fresh, "opening_balances")).isEqualTo("optional");

        applyTemplate(entity, "schedule_c");

        JsonNode after = setup();
        assertThat(status(after, "chart_of_accounts")).isEqualTo("done");
        assertThat(step(after, "chart_of_accounts").get("detail").asText()).contains("account(s)");
        assertThat(status(after, "first_entry")).isEqualTo("todo");
    }

    @Test
    void ac3_ac4_ac5_ac6_workingThroughTheListFinishesIt() {
        Map<String, String> codes = applyTemplate(entity, "schedule_c");
        api.patch("/api/v1/orgs/" + org + "/entities/" + entity, Map.of("homeState", "TX"), HttpStatus.OK);
        api.post(base + "/bank-accounts",
                Map.of("name", "Checking", "glAccountId", codes.get("1010")), HttpStatus.CREATED);

        // AC4: a sole proprietor must map its accounts; the template already does, so this is done.
        assertThat(status(setup(), "tax_lines")).isEqualTo("done");

        // AC3: an optional step that has been done says done.
        api.post(base + "/opening-balances", Map.of("asOfDate", "2026-01-01",
                        "equityAccountId", codes.get("3010"),
                        "balances", List.of(Map.of("accountId", codes.get("1010"),
                                "amount", Map.of("amount", "1000.00", "currency", "USD")))),
                HttpStatus.CREATED);
        assertThat(status(setup(), "opening_balances")).isEqualTo("done");

        JsonNode done = setup();
        assertThat(done.get("complete").asBoolean()).isTrue();
        assertThat(done.get("doneCount").asInt()).isEqualTo(done.get("requiredCount").asInt());

        // AC6: asking the question changed nothing.
        assertThat(api.get(base + "/accounts")).hasSize(codes.size());
        assertThat(api.get(base + "/journal-entries")).hasSize(1);
    }

    @Test
    void ac4_aHouseholdIsNotAskedToMapScheduleCLines() {
        String household = api.newEntity(org, "individual");
        applyTemplate(household, "personal");

        JsonNode setup = api.get("/api/v1/orgs/" + org + "/entities/" + household + "/setup");

        assertThat(status(setup, "tax_lines")).isEqualTo("optional");
        assertThat(step(setup, "tax_lines").get("detail").asText()).contains("no Schedule C");
    }

    @Test
    void anUnmappedAccountKeepsTheTaxLineStepOpenForABusiness() {
        applyTemplate(entity, "schedule_c");
        api.post(base + "/accounts", Map.of("code", "6999", "name", "Odds and ends", "type", "expense"),
                HttpStatus.CREATED);

        assertThat(status(setup(), "tax_lines")).isEqualTo("todo");
        assertThat(step(setup(), "tax_lines").get("detail").asText()).contains("1 income or expense account(s)");
    }

    @Test
    void ac7_anotherOrganizationSeesNothing() {
        new ApiClient(rest).get(base + "/setup", HttpStatus.NOT_FOUND);
    }
}
