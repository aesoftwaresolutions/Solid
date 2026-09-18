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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Spec 010. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaxLineReportTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));

        post("2026-01-15", "1010", "4010", "2500.00", true);   // revenue
        post("2026-02-01", "6010", "1010", "300.00", true);    // advertising  → L8
        post("2026-03-01", "6200", "1010", "120.00", true);    // utilities    → L25
        post("2026-03-02", "6240", "1010", "80.00", true);     // telephone    → L25 as well
        post("2026-04-01", "3020", "1010", "500.00", true);    // owner draw (not on Schedule C)
        post("2026-05-01", "6220", "1010", "54.99", false);    // draft: not counted, flagged
    }

    private void post(String date, String debitCode, String creditCode, String amount, boolean posted) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", posted);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debitCode), "amount", Map.of("amount", amount, "currency", "USD")),
                Map.of("accountId", acct.get(creditCode), "amount", Map.of("amount", "-" + amount, "currency", "USD"))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    private JsonNode lineByCode(JsonNode report, String code) {
        for (JsonNode line : report.get("lines")) {
            if (line.get("code").asText().equals(code)) {
                return line;
            }
        }
        throw new AssertionError("no tax line " + code + " in " + report.get("lines"));
    }

    @Test
    void ac1_ac2_rollsUpAccountsToScheduleCLines() {
        JsonNode report = api.get(base + "/reports/tax-lines?taxYear=2026");
        assertThat(report.get("from").asText()).isEqualTo("2026-01-01");
        assertThat(report.get("to").asText()).isEqualTo("2026-12-31");

        assertThat(amt(lineByCode(report, "F1040.SCH_C.L1").get("amount"))).isEqualTo("2500.00");
        assertThat(amt(lineByCode(report, "F1040.SCH_C.L8").get("amount"))).isEqualTo("300.00");

        JsonNode utilities = lineByCode(report, "F1040.SCH_C.L25");
        assertThat(amt(utilities.get("amount"))).isEqualTo("200.00");
        assertThat(utilities.get("accounts")).hasSize(2);
        assertThat(utilities.get("accounts").get(0).get("code").asText()).isEqualTo("6200");

        assertThat(report.get("lines").toString()).doesNotContain("Owner's Draws");
        assertThat(report.get("lines").get(0).get("line").asText()).as("income first, in form order").isEqualTo("L1");

        assertThat(amt(report.get("totals").get("income"))).isEqualTo("2500.00");
        assertThat(amt(report.get("totals").get("expenses"))).isEqualTo("500.00");
        assertThat(amt(report.get("totals").get("netProfit"))).isEqualTo("2000.00");

        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31");
        assertThat(amt(report.get("totals").get("netProfit"))).isEqualTo(amt(pl.get("netIncome")));
    }

    @Test
    void ac3_ac4_unmappedAccountsAndReadiness() {
        JsonNode before = api.get(base + "/reports/tax-lines?taxYear=2026");
        assertThat(before.get("unmapped")).isEmpty();
        assertThat(before.get("readiness").get("draftEntries").asInt()).isEqualTo(1);
        assertThat(before.get("readiness").get("ready").asBoolean()).isFalse();

        // Clear the tax line from Advertising: it becomes a question for the preparer.
        api.patch(base + "/accounts/" + acct.get("6010"), Map.of("taxLineCode", ""), HttpStatus.OK);
        JsonNode after = api.get(base + "/reports/tax-lines?taxYear=2026");
        assertThat(after.get("unmapped")).hasSize(1);
        assertThat(after.get("unmapped").get(0).get("code").asText()).isEqualTo("6010");
        assertThat(amt(after.get("unmapped").get(0).get("amount"))).isEqualTo("300.00");
        assertThat(amt(after.get("totals").get("expenses"))).as("still counted in totals").isEqualTo("500.00");
        assertThat(after.get("readiness").get("unmappedAccounts").asInt()).isEqualTo(1);
    }

    @Test
    void ac5_fiscalYearEntityUsesItsOwnYear() {
        String fiscal = api.post("/api/v1/orgs/" + org + "/entities",
                Map.of("kind", "smllc", "legalName", "June Fiscal LLC", "fiscalYearEnd", 6), HttpStatus.CREATED)
                .get("id").asText();
        JsonNode report = api.get("/api/v1/orgs/" + org + "/entities/" + fiscal + "/reports/tax-lines?taxYear=2026");
        assertThat(report.get("from").asText()).isEqualTo("2025-07-01");
        assertThat(report.get("to").asText()).isEqualTo("2026-06-30");
        assertThat(report.get("lines")).isEmpty();
        assertThat(report.get("readiness").get("ready").asBoolean()).isTrue();
    }

    @Test
    void ac6_csvExportMatchesJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<String> csv = rest.exchange(base + "/reports/tax-lines.csv?taxYear=2026", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getFirst("Content-Disposition")).contains("solid-tax-lines-2026.csv");
        String body = csv.getBody();
        assertThat(body).startsWith("Section,Form,Line,Label,Account Code,Account Name,Amount\n");
        assertThat(body).contains("expense,F1040.SCH_C,L8,Advertising,6010,Advertising and Marketing,300.00");
        assertThat(body).contains("totals,,,Net profit,,,2000.00");
        assertThat(body).contains("readiness,,,Draft entries,,,1");
    }

    @Test
    void ac1_invalidTaxYearIsRejected() {
        api.get(base + "/reports/tax-lines?taxYear=1799", HttpStatus.BAD_REQUEST);
    }
}
