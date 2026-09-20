package com.aesoftwaresolutions.solid.salestax;

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

/** Spec 037. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SalesTaxApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    String customerId;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers", Map.of("name", "Northwind Traders"), HttpStatus.CREATED)
                .get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private static String amt(JsonNode node) {
        return node.get("amount").asText();
    }

    private String rate(String jurisdiction, String percent, String from) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jurisdiction", jurisdiction);
        body.put("ratePercent", percent);
        body.put("liabilityAccountId", acct.get("2200"));
        body.put("effectiveFrom", from);
        body.put("note", "From the state DOR page; verify each year.");
        return api.post(base + "/sales-tax-rates", body, HttpStatus.CREATED).get("id").asText();
    }

    private JsonNode invoice(List<Map<String, Object>> lines, String issueDate) {
        return api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", issueDate,
                "terms", "net_30", "lines", lines), HttpStatus.CREATED);
    }

    private Map<String, Object> line(String description, String unitPrice, String taxRateId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("description", description);
        line.put("quantity", "1");
        line.put("unitPrice", money(unitPrice));
        line.put("incomeAccountId", acct.get("4010"));
        if (taxRateId != null) {
            line.put("taxRateId", taxRateId);
        }
        return line;
    }

    @Test
    void ac1_ac2_ratesAreEnteredAndAppliedToALine() {
        String rateId = rate("Springfield, IL", "8.2500", "2026-01-01");

        JsonNode rates = api.get(base + "/sales-tax-rates");
        assertThat(rates).hasSize(1);
        assertThat(rates.get(0).get("jurisdiction").asText()).isEqualTo("Springfield, IL");
        assertThat(rates.get(0).get("note").asText()).contains("verify");

        JsonNode invoice = invoice(List.of(line("Consulting", "1000.00", rateId)), "2026-03-01");
        assertThat(amt(invoice.get("lines").get(0).get("taxAmount"))).isEqualTo("82.50");
        assertThat(amt(invoice.get("taxTotal"))).isEqualTo("82.50");
        assertThat(amt(invoice.get("total"))).as("the customer owes net plus tax").isEqualTo("1082.50");
    }

    @Test
    void ac3_taxIsCreditedToTheLiabilityAccountAndNeverToIncome() {
        String rateId = rate("Springfield, IL", "8.2500", "2026-01-01");
        String invoiceId = invoice(List.of(line("Consulting", "1000.00", rateId)), "2026-03-01")
                .get("id").asText();
        api.post(base + "/invoices/" + invoiceId + "/finalize", Map.of(), HttpStatus.OK);

        JsonNode entry = api.get(base + "/journal-entries?from=2026-03-01&to=2026-03-01").get(0);
        Map<String, String> byAccount = new LinkedHashMap<>();
        entry.get("lines").forEach(l -> byAccount.put(l.get("accountId").asText(), amt(l.get("amount"))));

        assertThat(byAccount.get(acct.get("1100"))).as("receivable is the gross").isEqualTo("1082.50");
        assertThat(byAccount.get(acct.get("4010"))).as("income is the net").isEqualTo("-1000.00");
        assertThat(byAccount.get(acct.get("2200"))).as("tax is a liability").isEqualTo("-82.50");

        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31");
        assertThat(amt(pl.get("income").get("total"))).as("the P&L never sees the tax").isEqualTo("1000.00");
    }

    @Test
    void ac4_taxRoundsPerLineHalfUp() {
        String rateId = rate("Springfield, IL", "8.2500", "2026-01-01");
        JsonNode invoice = invoice(List.of(
                line("One", "33.33", rateId), line("Two", "33.33", rateId), line("Three", "33.33", rateId)),
                "2026-03-01");

        // 33.33 * 8.25% = 2.7497... -> 2.75 on each line, so 8.25 in total (not 8.24 on the invoice total).
        invoice.get("lines").forEach(line -> assertThat(amt(line.get("taxAmount"))).isEqualTo("2.75"));
        assertThat(amt(invoice.get("taxTotal"))).isEqualTo("8.25");
        assertThat(amt(invoice.get("total"))).isEqualTo("108.24");
    }

    @Test
    void ac5_ratesMustBelongHereBeActiveAndBeEffective() {
        String rateId = rate("Springfield, IL", "8.2500", "2026-06-01");

        // Before it applies.
        api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", "2026-03-01", "terms", "net_30",
                "lines", List.of(line("Early", "100.00", rateId))), HttpStatus.CONFLICT);

        // From another entity.
        String other = api.newEntity(org, "sole_prop");
        Map<String, String> otherAcct = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + other + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> otherAcct.put(a.get("code").asText(), a.get("id").asText()));
        String foreignRate = api.post("/api/v1/orgs/" + org + "/entities/" + other + "/sales-tax-rates",
                Map.of("jurisdiction", "Elsewhere", "ratePercent", "5.0000",
                        "liabilityAccountId", otherAcct.get("2200"), "effectiveFrom", "2026-01-01"),
                HttpStatus.CREATED).get("id").asText();
        api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", "2026-07-01", "terms", "net_30",
                "lines", List.of(line("Foreign rate", "100.00", foreignRate))), HttpStatus.BAD_REQUEST);

        // An income account is not somewhere tax can be owed.
        api.post(base + "/sales-tax-rates", Map.of("jurisdiction", "Bad", "ratePercent", "1.0000",
                "liabilityAccountId", acct.get("4010"), "effectiveFrom", "2026-01-01"), HttpStatus.CONFLICT);
    }

    @Test
    void ac6_ac7_theReportGroupsByJurisdictionAndDeactivationLeavesHistoryAlone() {
        String illinois = rate("Springfield, IL", "8.2500", "2026-01-01");
        String indiana = rate("Indianapolis, IN", "7.0000", "2026-01-01");

        String first = invoice(List.of(line("Consulting", "1000.00", illinois)), "2026-03-01").get("id").asText();
        api.post(base + "/invoices/" + first + "/finalize", Map.of(), HttpStatus.OK);
        String second = invoice(List.of(line("Consulting", "500.00", indiana)), "2026-03-15").get("id").asText();
        api.post(base + "/invoices/" + second + "/finalize", Map.of(), HttpStatus.OK);

        // A draft and a voided invoice must not count.
        invoice(List.of(line("Draft", "9999.00", illinois)), "2026-03-20");
        String voided = invoice(List.of(line("Cancelled", "777.00", illinois)), "2026-03-21").get("id").asText();
        api.post(base + "/invoices/" + voided + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/invoices/" + voided + "/void", Map.of(), HttpStatus.OK);

        JsonNode report = api.get(base + "/reports/sales-tax?from=2026-03-01&to=2026-03-31");
        assertThat(report.get("jurisdictions")).hasSize(2);
        Map<String, String> collected = new LinkedHashMap<>();
        report.get("jurisdictions").forEach(j ->
                collected.put(j.get("jurisdiction").asText(), amt(j.get("taxCollected"))));
        assertThat(collected).containsEntry("Springfield, IL", "82.50").containsEntry("Indianapolis, IN", "35.00");
        assertThat(amt(report.get("totalTaxable"))).isEqualTo("1500.00");
        assertThat(amt(report.get("totalCollected"))).isEqualTo("117.50");
        assertThat(report.get("note").asText()).contains("holding for the state").contains("yours to do");

        // AC7: deactivating keeps history and blocks new use.
        api.post(base + "/sales-tax-rates/" + illinois + "/deactivate", Map.of(), HttpStatus.OK);
        assertThat(amt(api.get(base + "/reports/sales-tax?from=2026-03-01&to=2026-03-31").get("totalCollected")))
                .isEqualTo("117.50");
        api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", "2026-04-01", "terms", "net_30",
                "lines", List.of(line("After", "100.00", illinois))), HttpStatus.CONFLICT);
    }

    @Test
    void invoicesWithoutTaxAreUnchanged() {
        JsonNode invoice = invoice(List.of(line("Consulting", "1000.00", null)), "2026-03-01");
        assertThat(amt(invoice.get("taxTotal"))).isEqualTo("0.00");
        assertThat(amt(invoice.get("total"))).isEqualTo("1000.00");
        assertThat(invoice.get("lines").get(0).get("taxRateId").isNull()).isTrue();
    }
}
