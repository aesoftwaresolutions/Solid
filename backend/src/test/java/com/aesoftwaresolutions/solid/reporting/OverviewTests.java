package com.aesoftwaresolutions.solid.reporting;

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

/** Spec 041. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OverviewTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
    }

    private String entity(String name, String kind) {
        return api.post("/api/v1/orgs/" + org + "/entities", Map.of("kind", kind, "legalName", name),
                HttpStatus.CREATED).get("id").asText();
    }

    private Map<String, String> chart(String entityId, String template) {
        Map<String, String> codes = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + entityId + "/accounts/apply-template",
                        Map.of("template", template), HttpStatus.CREATED)
                .forEach(a -> codes.put(a.get("code").asText(), a.get("id").asText()));
        return codes;
    }

    private void post(String entityId, Map<String, String> codes, String date, String debit, String credit,
                      String amount, boolean posted) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", posted);
        body.put("lines", List.of(
                Map.of("accountId", codes.get(debit), "amount", Map.of("amount", amount, "currency", "USD")),
                Map.of("accountId", codes.get(credit), "amount", Map.of("amount", "-" + amount, "currency", "USD"))));
        api.post("/api/v1/orgs/" + org + "/entities/" + entityId + "/journal-entries", body, HttpStatus.CREATED);
    }

    private JsonNode overview(String from, String to) {
        return api.get("/api/v1/orgs/" + org + "/overview?from=" + from + "&to=" + to);
    }

    private static JsonNode line(JsonNode result, String name) {
        return java.util.stream.StreamSupport.stream(result.get("entities").spliterator(), false)
                .filter(e -> e.get("legalName").asText().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void ac1_ac2_ac3_ac5_everyEntityOnOneLineAndTheTotalIsTheSum() {
        String shop = entity("Zeta Shop LLC", "smllc");
        String home = entity("Alpha Household", "individual");
        Map<String, String> shopCodes = chart(shop, "schedule_c");
        Map<String, String> homeCodes = chart(home, "personal");

        post(shop, shopCodes, "2026-02-01", "1010", "4010", "3000.00", true);   // sale, paid in
        post(shop, shopCodes, "2026-02-10", "6220", "1010", "200.00", true);    // software
        post(shop, shopCodes, "2026-03-01", "6220", "1010", "50.00", false);    // a draft, waiting
        post(home, homeCodes, "2026-02-05", "1010", "4010", "1000.00", true);

        JsonNode result = overview("2026-01-01", "2026-12-31");

        // AC1: in name order, with each entity's own currency.
        assertThat(result.get("entities")).hasSize(2);
        assertThat(result.get("entities").get(0).get("legalName").asText()).isEqualTo("Alpha Household");
        assertThat(line(result, "Zeta Shop LLC").get("currency").asText()).isEqualTo("USD");

        // AC2: the same figures the entity's own reports give.
        JsonNode shopLine = line(result, "Zeta Shop LLC");
        JsonNode shopCashFlow = api.get("/api/v1/orgs/" + org + "/entities/" + shop
                + "/reports/cash-flow?from=2026-01-01&to=2026-12-31");
        JsonNode shopPnl = api.get("/api/v1/orgs/" + org + "/entities/" + shop
                + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31");
        assertThat(shopLine.get("cash").get("amount").asText())
                .isEqualTo(shopCashFlow.get("closingCash").get("amount").asText())
                .isEqualTo("2800.00");
        assertThat(shopLine.get("netIncome").get("amount").asText())
                .isEqualTo(shopPnl.get("netIncome").get("amount").asText())
                .isEqualTo("2800.00");

        // AC5: the work waiting on that entity, counted per entity.
        assertThat(shopLine.get("draftEntries").asInt()).isEqualTo(1);
        assertThat(shopLine.get("needsAttention").asBoolean()).isTrue();
        assertThat(line(result, "Alpha Household").get("needsAttention").asBoolean()).isFalse();

        // AC3: one currency, so the totals are the plain sum.
        assertThat(result.get("mixedCurrencies").asBoolean()).isFalse();
        assertThat(result.get("totals").get("cash").get("amount").asText()).isEqualTo("3800.00");
        assertThat(result.get("totals").get("netIncome").get("amount").asText()).isEqualTo("3800.00");
        assertThat(result.get("note").asText()).contains("not a consolidation");
    }

    @Test
    void ac4_twoCurrenciesAreNeverAddedTogether() {
        entity("US Shop", "smllc");
        api.post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "smllc", "legalName", "UK Shop", "baseCurrency", "GBP"), HttpStatus.CREATED);

        JsonNode result = overview("2026-01-01", "2026-12-31");

        assertThat(result.get("mixedCurrencies").asBoolean()).isTrue();
        assertThat(result.get("totals").isNull()).isTrue();
        assertThat(line(result, "UK Shop").get("currency").asText()).isEqualTo("GBP");
    }

    @Test
    void ac6_aBrandNewEntityIsNotSetUpYetRatherThanAnError() {
        entity("Fresh LLC", "smllc");

        JsonNode result = overview("2026-01-01", "2026-12-31");

        JsonNode fresh = line(result, "Fresh LLC");
        assertThat(fresh.get("setUp").asBoolean()).isFalse();
        assertThat(fresh.get("cash").get("amount").asText()).isEqualTo("0.00");
        assertThat(fresh.get("netIncome").get("amount").asText()).isEqualTo("0.00");
    }

    /**
     * Spec 043: with no period asked for, each line uses that entity's own fiscal year, so the overview and
     * the entity's own reports show the same profit.
     */
    @Test
    void anEntityWhoseYearEndsInJuneGetsItsOwnYear() {
        String shop = api.post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "smllc", "legalName", "June Year LLC", "fiscalYearEnd", 6), HttpStatus.CREATED)
                .get("id").asText();
        Map<String, String> codes = chart(shop, "schedule_c");
        post(shop, codes, java.time.LocalDate.now().withMonth(2).withDayOfMonth(1).toString(),
                "1010", "4010", "500.00", true);   // February: last fiscal year if the year ends in June

        JsonNode result = api.get("/api/v1/orgs/" + org + "/overview");
        JsonNode line = line(result, "June Year LLC");

        assertThat(result.get("from").isNull()).as("no period was asked for").isTrue();
        assertThat(line.get("from").asText()).endsWith("-07-01");
        JsonNode pnl = api.get("/api/v1/orgs/" + org + "/entities/" + shop + "/reports/profit-and-loss?from="
                + line.get("from").asText() + "&to=" + line.get("to").asText());
        assertThat(line.get("netIncome").get("amount").asText())
                .isEqualTo(pnl.get("netIncome").get("amount").asText());
    }

    @Test
    void ac7_anotherOrganizationSeesNothing() {
        entity("Zeta Shop LLC", "smllc");
        new ApiClient(rest).get("/api/v1/orgs/" + org + "/overview", HttpStatus.NOT_FOUND);
        api.get("/api/v1/orgs/" + org + "/overview?from=2026-12-31&to=2026-01-01", HttpStatus.BAD_REQUEST);
    }
}
