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

/**
 * Spec 006 AC 1–3, 5: reports for the golden ledger in
 * {@code src/test/resources/fixtures/ledger-sample-2026.md}. Expected values are hand-computed there.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FinancialReportsGoldenTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void loadGoldenLedger() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));

        post("2025-12-15", "1010", "4010", "400.00", true);
        post("2026-01-02", "1010", "3010", "5000.00", true);
        post("2026-01-15", "1010", "4010", "2500.00", true);
        post("2026-01-20", "6220", "1010", "54.99", true);
        post("2026-02-01", "6010", "2010", "300.00", true);
        post("2026-02-10", "3020", "1010", "1000.00", true);
        post("2026-02-15", "4020", "1010", "100.00", true);
        post("2026-03-01", "1500", "1010", "1200.00", true);
        post("2026-03-05", "2010", "1010", "300.00", true);
        post("2026-03-10", "1100", "4010", "800.00", true);
        String mistake = post("2026-03-15", "6160", "1010", "75.00", true);
        api.post(base + "/journal-entries/" + mistake + "/reverse", Map.of(), HttpStatus.CREATED);
        post("2026-12-31", "6050", "1510", "240.00", true);
        post("2026-04-01", "6900", "1010", "999.00", false); // draft: must never appear
    }

    private String post(String date, String debitCode, String creditCode, String amount, boolean posted) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", posted);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debitCode), "amount", Map.of("amount", amount, "currency", "USD")),
                Map.of("accountId", acct.get(creditCode), "amount", Map.of("amount", "-" + amount, "currency", "USD"))));
        return api.post(base + "/journal-entries", body, HttpStatus.CREATED).get("id").asText();
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    private static Map<String, String> rowsByCode(JsonNode section) {
        Map<String, String> result = new LinkedHashMap<>();
        section.get("rows").forEach(r -> result.put(r.get("code").isNull() ? r.get("name").asText() : r.get("code").asText(),
                amt(r.get("amount"))));
        return result;
    }

    @Test
    void trialBalanceMatchesGoldenFigures() {
        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-12-31");

        Map<String, String> debits = new LinkedHashMap<>();
        Map<String, String> credits = new LinkedHashMap<>();
        tb.get("rows").forEach(r -> {
            if (!amt(r.get("debit")).equals("0.00")) {
                debits.put(r.get("code").asText(), amt(r.get("debit")));
            }
            if (!amt(r.get("credit")).equals("0.00")) {
                credits.put(r.get("code").asText(), amt(r.get("credit")));
            }
        });

        assertThat(debits).containsExactlyEntriesOf(ordered(
                "1010", "5245.01", "1100", "800.00", "1500", "1200.00", "3020", "1000.00",
                "4020", "100.00", "6010", "300.00", "6050", "240.00", "6220", "54.99"));
        assertThat(credits).containsExactlyEntriesOf(ordered("1510", "240.00", "3010", "5000.00", "4010", "3700.00"));
        assertThat(amt(tb.get("totalDebit"))).isEqualTo("8940.00");
        assertThat(amt(tb.get("totalCredit"))).isEqualTo("8940.00");
    }

    @Test
    void profitAndLossMatchesGoldenFigures() {
        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31");

        assertThat(rowsByCode(pl.get("income"))).containsExactlyEntriesOf(ordered("4010", "3300.00", "4020", "-100.00"));
        assertThat(amt(pl.get("income").get("total"))).isEqualTo("3200.00");
        assertThat(pl.get("costOfGoodsSold").get("rows")).isEmpty();
        assertThat(amt(pl.get("grossProfit"))).isEqualTo("3200.00");
        assertThat(rowsByCode(pl.get("expenses")))
                .containsExactlyEntriesOf(ordered("6010", "300.00", "6050", "240.00", "6220", "54.99"));
        assertThat(amt(pl.get("expenses").get("total"))).isEqualTo("594.99");
        assertThat(amt(pl.get("netIncome"))).isEqualTo("2605.01");
    }

    @Test
    void balanceSheetMatchesGoldenFigures() {
        JsonNode bs = api.get(base + "/reports/balance-sheet?asOf=2026-12-31");

        assertThat(bs.get("fiscalYearStart").asText()).isEqualTo("2026-01-01");
        assertThat(rowsByCode(bs.get("assets")))
                .containsExactlyEntriesOf(ordered("1010", "5245.01", "1100", "800.00", "1500", "1200.00", "1510", "-240.00"));
        assertThat(amt(bs.get("assets").get("total"))).isEqualTo("7005.01");
        assertThat(amt(bs.get("liabilities").get("total"))).isEqualTo("0.00");
        assertThat(rowsByCode(bs.get("equity"))).containsExactlyEntriesOf(ordered(
                "3010", "5000.00", "3020", "-1000.00",
                "Retained Earnings (prior years)", "400.00", "Current Year Earnings", "2605.01"));
        assertThat(amt(bs.get("totalLiabilitiesAndEquity"))).isEqualTo("7005.01");
        assertThat(bs.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void dateFiltersAreInclusiveAndValidated() {
        JsonNode jan15 = api.get(base + "/reports/profit-and-loss?from=2026-01-15&to=2026-01-15");
        assertThat(amt(jan15.get("netIncome"))).isEqualTo("2500.00");

        JsonNode q1 = api.get(base + "/reports/profit-and-loss?from=2026-01-01&to=2026-03-31");
        assertThat(amt(q1.get("netIncome"))).isEqualTo("2845.01"); // 3200 - 300 - 54.99

        api.get(base + "/reports/profit-and-loss?from=2026-02-01&to=2026-01-01", HttpStatus.BAD_REQUEST);
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
