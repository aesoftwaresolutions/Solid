package com.aesoftwaresolutions.solid.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** Spec 039. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CashFlowTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private void post(String date, String debitCode, String creditCode, String amount, boolean posted) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", posted);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debitCode), "amount", money(amount)),
                Map.of("accountId", acct.get(creditCode), "amount", money("-" + amount))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private JsonNode cashFlow(String from, String to) {
        return api.get(base + "/reports/cash-flow?from=" + from + "&to=" + to);
    }

    private static String amt(JsonNode node) {
        return node.get("amount").asText();
    }

    private static Map<String, String> rows(JsonNode section) {
        Map<String, String> result = new LinkedHashMap<>();
        section.get("rows").forEach(row -> result.put(
                row.get("code").isNull() ? row.get("name").asText() : row.get("code").asText(), amt(row.get("amount"))));
        return result;
    }

    @Test
    void ac1_ac2_ac5_itTiesToCashAndSortsMovementsByWhereTheyCameFrom() {
        post("2025-12-20", "1010", "3010", "5000.00", true);   // opening contribution, before the period
        post("2026-02-01", "1010", "4010", "2500.00", true);   // customer paid: operating
        post("2026-02-10", "6220", "1010", "54.99", true);     // software: operating
        post("2026-03-01", "1500", "1010", "1200.00", true);   // bought equipment: investing
        post("2026-03-10", "3020", "1010", "1000.00", true);   // owner's draw: financing
        post("2026-04-01", "6900", "1010", "999.00", false);   // a draft is not cash

        JsonNode report = cashFlow("2026-01-01", "2026-12-31");

        assertThat(amt(report.get("openingCash"))).isEqualTo("5000.00");
        assertThat(amt(report.get("operating").get("total"))).isEqualTo("2445.01");
        assertThat(amt(report.get("investing").get("total"))).isEqualTo("-1200.00");
        assertThat(amt(report.get("financing").get("total"))).isEqualTo("-1000.00");
        assertThat(amt(report.get("netChange"))).isEqualTo("245.01");
        assertThat(amt(report.get("closingCash"))).isEqualTo("5245.01");
        assertThat(report.get("unclassified").get("rows")).isEmpty();
        assertThat(report.get("note").asText()).contains("direct method");

        // AC1: the two invariants a cash-flow statement lives or dies by.
        long opening = cents(amt(report.get("openingCash")));
        long closing = cents(amt(report.get("closingCash")));
        long net = cents(amt(report.get("netChange")));
        long sections = cents(amt(report.get("operating").get("total")))
                + cents(amt(report.get("investing").get("total")))
                + cents(amt(report.get("financing").get("total")))
                + cents(amt(report.get("unclassified").get("total")));
        assertThat(opening + net).isEqualTo(closing);
        assertThat(sections).isEqualTo(net);

        // AC5: the draft never appears.
        assertThat(report.toString()).doesNotContain("999.00");
    }

    @Test
    void ac3_aTransferBetweenBankAccountsIsNotCashFlow() {
        post("2026-01-05", "1010", "3010", "5000.00", true);
        post("2026-02-01", "1020", "1010", "2000.00", true);   // checking -> savings

        JsonNode report = cashFlow("2026-02-01", "2026-02-28");

        assertThat(amt(report.get("netChange"))).isEqualTo("0.00");
        assertThat(report.get("operating").get("rows")).isEmpty();
        assertThat(report.get("investing").get("rows")).isEmpty();
        assertThat(report.get("financing").get("rows")).isEmpty();
        assertThat(report.get("unclassified").get("rows")).isEmpty();
    }

    @Test
    void ac4_ac6_anEntrySpanningCategoriesIsSplitAndTraceable() {
        post("2026-01-05", "1010", "3010", "5000.00", true);

        // One payment covering an expense and a laptop: 300.01 of software, 1200 of equipment.
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", "2026-02-01");
        body.put("post", true);
        body.put("lines", List.of(
                Map.of("accountId", acct.get("6220"), "amount", money("300.01")),
                Map.of("accountId", acct.get("1500"), "amount", money("1200.00")),
                Map.of("accountId", acct.get("1010"), "amount", money("-1500.01"))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);

        JsonNode report = cashFlow("2026-02-01", "2026-02-28");

        long operating = cents(amt(report.get("operating").get("total")));
        long investing = cents(amt(report.get("investing").get("total")));
        assertThat(operating + investing).as("the split sums to the cash that moved").isEqualTo(-150001L);
        assertThat(operating).isEqualTo(-30001L);
        assertThat(investing).isEqualTo(-120000L);

        // AC6: the counterpart accounts are named, so a figure can be traced.
        assertThat(rows(report.get("operating"))).containsKey("6220");
        assertThat(rows(report.get("investing"))).containsKey("1500");
    }

    @Test
    void ac7_scopedToTheOrganization() {
        new ApiClient(rest).get(base + "/reports/cash-flow?from=2026-01-01&to=2026-12-31", HttpStatus.NOT_FOUND);
        api.get(base + "/reports/cash-flow?from=2026-12-31&to=2026-01-01", HttpStatus.BAD_REQUEST);
    }

    private static long cents(String decimal) {
        return Math.round(Double.parseDouble(decimal) * 100);
    }
}
