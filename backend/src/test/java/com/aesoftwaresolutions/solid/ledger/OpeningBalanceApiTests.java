package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 028. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpeningBalanceApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private Map<String, Object> balance(String code, String amount) {
        return Map.of("accountId", acct.get(code), "amount", money(amount));
    }

    private JsonNode submit(List<Map<String, Object>> balances, String equityCode, HttpStatus expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOfDate", "2026-01-01");
        body.put("balances", balances);
        if (equityCode != null) {
            body.put("equityAccountId", acct.get(equityCode));
        }
        return api.post(base + "/opening-balances", body, expected);
    }

    private static Map<String, String> linesByAccount(JsonNode entry) {
        Map<String, String> result = new LinkedHashMap<>();
        entry.get("lines").forEach(line ->
                result.put(line.get("accountId").asText(), line.get("amount").get("amount").asText()));
        return result;
    }

    @Test
    void ac1_ac2_naturalBalancesBecomeOneBalancedPostedEntry() {
        JsonNode entry = submit(List.of(balance("1010", "5000.00"), balance("2010", "1200.00")), "3010",
                HttpStatus.CREATED);

        assertThat(entry.get("status").asText()).isEqualTo("posted");
        assertThat(entry.get("source").asText()).isEqualTo("opening_balance");
        assertThat(entry.get("entryDate").asText()).isEqualTo("2026-01-01");

        Map<String, String> lines = linesByAccount(entry);
        assertThat(lines.get(acct.get("1010"))).as("assets are debited").isEqualTo("5000.00");
        assertThat(lines.get(acct.get("2010"))).as("liabilities are credited").isEqualTo("-1200.00");
        assertThat(lines.get(acct.get("3010"))).as("the difference is opening equity").isEqualTo("-3800.00");

        JsonNode balanceSheet = api.get(base + "/reports/balance-sheet?asOf=2026-01-01");
        assertThat(balanceSheet.get("balanced").asBoolean()).isTrue();
        assertThat(balanceSheet.get("assets").get("total").get("amount").asText()).isEqualTo("5000.00");
        assertThat(balanceSheet.get("liabilities").get("total").get("amount").asText()).isEqualTo("1200.00");
    }

    @Test
    void ac3_aNegativeAssetBalanceIsACredit() {
        JsonNode entry = submit(List.of(balance("1010", "-250.00")), "3010", HttpStatus.CREATED);

        Map<String, String> lines = linesByAccount(entry);
        assertThat(lines.get(acct.get("1010"))).isEqualTo("-250.00");
        assertThat(lines.get(acct.get("3010"))).isEqualTo("250.00");
    }

    @Test
    void ac4_ac7_onlyOneSetStandsAtATime() {
        api.get(base + "/opening-balances", HttpStatus.NOT_FOUND);

        JsonNode first = submit(List.of(balance("1010", "5000.00")), "3010", HttpStatus.CREATED);
        assertThat(api.get(base + "/opening-balances").get("id").asText()).isEqualTo(first.get("id").asText());

        JsonNode refused = submit(List.of(balance("1010", "10.00")), "3010", HttpStatus.CONFLICT);
        assertThat(refused.get("code").asText()).isEqualTo("OPENING_BALANCES_EXIST");
        assertThat(refused.get("detail").asText()).contains(first.get("id").asText());

        api.post(base + "/journal-entries/" + first.get("id").asText() + "/reverse", Map.of(), HttpStatus.CREATED);
        JsonNode second = submit(List.of(balance("1010", "7000.00")), "3010", HttpStatus.CREATED);
        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(api.get(base + "/opening-balances").get("id").asText()).isEqualTo(second.get("id").asText());
    }

    @Test
    void ac5_onlyRealPostableAccountsOfThisEntity() {
        submit(List.of(balance("1000", "10.00")), "3010", HttpStatus.BAD_REQUEST);          // header
        submit(List.of(balance("1010", "10.00"), balance("1010", "20.00")), "3010", HttpStatus.BAD_REQUEST);

        String other = api.newEntity(org, "sole_prop");
        String foreign = api.post("/api/v1/orgs/" + org + "/entities/" + other + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CREATED).get(1).get("id").asText();
        api.post(base + "/opening-balances",
                Map.of("asOfDate", "2026-01-01", "equityAccountId", acct.get("3010"),
                        "balances", List.of(Map.of("accountId", foreign, "amount", money("10.00")))),
                HttpStatus.BAD_REQUEST);

        api.patch(base + "/accounts/" + acct.get("1020"), Map.of("archived", true), HttpStatus.OK);
        submit(List.of(balance("1020", "10.00")), "3010", HttpStatus.BAD_REQUEST);

        // The balancing account must be equity, and cannot also carry a balance.
        api.post(base + "/opening-balances",
                Map.of("asOfDate", "2026-01-01", "equityAccountId", acct.get("1010"),
                        "balances", List.of(balance("1010", "10.00"))),
                HttpStatus.BAD_REQUEST);
        submit(List.of(balance("3010", "10.00")), "3010", HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac6_withoutAnOpeningBalanceAccountTheErrorSaysWhatToCreate() {
        JsonNode refused = submit(List.of(balance("1010", "5000.00")), null, HttpStatus.BAD_REQUEST);
        assertThat(refused.get("detail").asText())
                .contains("opening_balance")
                .contains("equityAccountId");

        // The personal template ships such an account, so there it works with no equityAccountId at all.
        String household = api.newEntity(org, "individual");
        String householdBase = "/api/v1/orgs/" + org + "/entities/" + household;
        Map<String, String> personal = new HashMap<>();
        api.post(householdBase + "/accounts/apply-template", Map.of("template", "personal"), HttpStatus.CREATED)
                .forEach(a -> personal.put(a.get("code").asText(), a.get("id").asText()));

        JsonNode entry = api.post(householdBase + "/opening-balances",
                Map.of("asOfDate", "2026-01-01", "balances",
                        List.of(Map.of("accountId", personal.get("1010"), "amount", money("2500.00")))),
                HttpStatus.CREATED);
        assertThat(linesByAccount(entry).get(personal.get("3010"))).isEqualTo("-2500.00");
    }

    @Test
    void zeroAmountsAreSkippedAndAnEmptySetIsRefused() {
        JsonNode entry = submit(List.of(balance("1010", "100.00"), balance("1020", "0.00")), "3010",
                HttpStatus.CREATED);
        assertThat(entry.get("lines")).hasSize(2);
        assertThat(linesByAccount(entry)).doesNotContainKey(acct.get("1020"));

        String other = api.newEntity(org, "sole_prop");
        Map<String, String> fresh = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + other + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> fresh.put(a.get("code").asText(), a.get("id").asText()));
        api.post("/api/v1/orgs/" + org + "/entities/" + other + "/opening-balances",
                Map.of("asOfDate", "2026-01-01", "equityAccountId", fresh.get("3010"),
                        "balances", List.of(Map.of("accountId", fresh.get("1010"), "amount", money("0.00")))),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void anotherOrganizationCannotSeeThem() {
        submit(List.of(balance("1010", "5000.00")), "3010", HttpStatus.CREATED);
        new ApiClient(rest).get(base + "/opening-balances", HttpStatus.NOT_FOUND);
        assertThat(UUID.fromString(acct.get("1010"))).isNotNull();
    }
}
