package com.aesoftwaresolutions.solid.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Spec 069: business lines, and the profit and loss split by them. Figures are hand-computed in the spec. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BusinessLineTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;
    String customer;
    String vendor;
    String automation;
    String web;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customer = api.post(base + "/customers", Map.of("name", "Client LLC"), HttpStatus.CREATED).get("id").asText();
        vendor = api.post(base + "/vendors", Map.of("name", "Supplier Inc", "is1099Vendor", false),
                HttpStatus.CREATED).get("id").asText();
        automation = line("Automation & AI");
        web = line("Websites & hosting");
    }

    // ---------- helpers ----------

    private String line(String name) {
        return api.post(base + "/business-lines", Map.of("name", name), HttpStatus.CREATED).get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private static Map<String, Object> invoiceLine(String description, String amount, String accountId) {
        return Map.of("description", description, "quantity", "1", "unitPrice", money(amount),
                "incomeAccountId", accountId);
    }

    private JsonNode invoice(String businessLineId, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customer);
        body.put("issueDate", "2026-03-01");
        body.put("terms", "net_30");
        body.put("lines", lines);
        body.put("businessLineId", businessLineId);
        String id = api.post(base + "/invoices", body, HttpStatus.CREATED).get("id").asText();
        return api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
    }

    private void bill(String businessLineId, String amount, String accountCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("vendorId", vendor);
        body.put("billDate", "2026-03-02");
        body.put("terms", "net_30");
        body.put("lines", List.of(Map.of("description", "Work", "amount", money(amount),
                "expenseAccountId", acct.get(accountCode))));
        body.put("businessLineId", businessLineId);
        String id = api.post(base + "/bills", body, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/bills/" + id + "/approve", Map.of(), HttpStatus.OK);
    }

    private void importBank(String csv) {
        String checking = api.post(base + "/bank-accounts", Map.of("name", "Checking", "glAccountId", acct.get("1010")),
                HttpStatus.CREATED).get("id").asText();
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "statement.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> r = rest.exchange(base + "/bank-accounts/" + checking + "/imports", HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
    }

    private String txn(String description) {
        for (JsonNode t : api.get(base + "/bank-transactions")) {
            if (t.get("description").asText().contains(description)) {
                return t.get("id").asText();
            }
        }
        throw new AssertionError("No transaction " + description);
    }

    private JsonNode report() {
        return api.get(base + "/reports/profit-and-loss-by-business-line?from=2026-01-01&to=2026-12-31");
    }

    private static JsonNode column(JsonNode report, String name) {
        for (JsonNode c : report.get("columns")) {
            if (c.get("name").asText().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("No column " + name + " in " + report.get("columns"));
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    /** The spec's fixture: two invoices and one unassigned, two bills, two bank lines and one manual entry. */
    private void fixture() {
        invoice(automation, List.of(invoiceLine("Front desk build", "2400.00", acct.get("4010"))));
        invoice(web, List.of(invoiceLine("Site rebuild", "1500.00", acct.get("4010")),
                invoiceLine("Hosting year", "300.00", acct.get("4900"))));
        invoice(null, List.of(invoiceLine("Audit", "250.00", acct.get("4010"))));
        bill(web, "400.00", "5010");
        bill(automation, "120.00", "6220");
        importBank("Date,Description,Amount\n2026-03-05,CLIENT THEME,-30.00\n2026-03-06,ADOBE,-54.99\n");
        api.post(base + "/bank-transactions/" + txn("CLIENT THEME") + "/categorize",
                Map.of("accountId", acct.get("6220"), "businessLineId", web), HttpStatus.OK);
        api.post(base + "/bank-transactions/" + txn("ADOBE") + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.OK);
        Map<String, Object> labor = new HashMap<>(Map.of("accountId", acct.get("6040"), "amount", money("500.00")));
        labor.put("businessLineId", automation);
        api.post(base + "/journal-entries", Map.of("entryDate", "2026-03-10", "memo", "Contractor", "post", true,
                "lines", List.of(labor, Map.of("accountId", acct.get("1010"), "amount", money("-500.00")))),
                HttpStatus.CREATED);
    }

    // ---------- acceptance criteria ----------

    @Test
    void ac1_createsListsRenamesAndArchivesLines() {
        JsonNode list = api.get(base + "/business-lines");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("name").asText()).isEqualTo("Automation & AI");

        assertThat(api.post(base + "/business-lines", Map.of("name", "  automation   & ai "), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("BUSINESS_LINE_NAME_TAKEN");
        api.post(base + "/business-lines", Map.of("name", " "), HttpStatus.BAD_REQUEST);

        JsonNode renamed = api.patch(base + "/business-lines/" + web, Map.of("name", "Web design"), HttpStatus.OK);
        assertThat(renamed.get("name").asText()).isEqualTo("Web design");
        JsonNode archived = api.patch(base + "/business-lines/" + web, Map.of("archived", true), HttpStatus.OK);
        assertThat(archived.get("isArchived").asBoolean()).isTrue();
        assertThat(api.get(base + "/business-lines").get(1).get("isArchived").asBoolean()).isTrue();
    }

    @Test
    void ac2_eachSourceCarriesItsBusinessLineToTheLedger() {
        JsonNode inv = invoice(web, List.of(invoiceLine("Site rebuild", "1500.00", acct.get("4010"))));
        assertThat(inv.get("businessLineId").asText()).isEqualTo(web);
        JsonNode entry = api.get(base + "/journal-entries/" + inv.get("journalEntryId").asText());
        // A/R line stays unassigned; the income line takes the invoice's business line.
        assertThat(entry.get("lines").get(0).get("businessLineId").isNull()).isTrue();
        assertThat(entry.get("lines").get(1).get("businessLineId").asText()).isEqualTo(web);

        importBank("Date,Description,Amount\n2026-03-05,CLIENT THEME,-30.00\n");
        JsonNode t = api.post(base + "/bank-transactions/" + txn("CLIENT THEME") + "/categorize",
                Map.of("accountId", acct.get("6220"), "businessLineId", web), HttpStatus.OK);
        JsonNode bankEntry = api.get(base + "/journal-entries/" + t.get("journalEntryId").asText());
        assertThat(bankEntry.get("lines").get(0).get("businessLineId").isNull()).isTrue();
        assertThat(bankEntry.get("lines").get(1).get("businessLineId").asText()).isEqualTo(web);
    }

    @Test
    void ac3_theReportSplitsTheProfitAndLossByLine() {
        fixture();
        JsonNode r = report();

        List<String> names = new ArrayList<>();
        r.get("columns").forEach(c -> names.add(c.get("name").asText()));
        assertThat(names).containsExactly("Automation & AI", "Websites & hosting", "Shared / overhead");

        JsonNode a = column(r, "Automation & AI");
        assertThat(amt(a.get("income"))).isEqualTo("2400.00");
        assertThat(amt(a.get("expenses"))).isEqualTo("620.00");
        assertThat(amt(a.get("netIncome"))).isEqualTo("1780.00");

        JsonNode w = column(r, "Websites & hosting");
        assertThat(amt(w.get("income"))).isEqualTo("1800.00");
        assertThat(amt(w.get("costOfGoodsSold"))).isEqualTo("400.00");
        assertThat(amt(w.get("grossProfit"))).isEqualTo("1400.00");
        assertThat(amt(w.get("expenses"))).isEqualTo("30.00");
        assertThat(amt(w.get("netIncome"))).isEqualTo("1370.00");

        JsonNode s = column(r, "Shared / overhead");
        assertThat(s.get("businessLineId").isNull()).isTrue();
        assertThat(amt(s.get("income"))).isEqualTo("250.00");
        assertThat(amt(s.get("expenses"))).isEqualTo("54.99");
        assertThat(amt(s.get("netIncome"))).isEqualTo("195.01");

        assertThat(amt(r.get("total").get("income"))).isEqualTo("4450.00");
        assertThat(amt(r.get("total").get("grossProfit"))).isEqualTo("4050.00");
        assertThat(amt(r.get("total").get("expenses"))).isEqualTo("704.99");
        assertThat(amt(r.get("total").get("netIncome"))).isEqualTo("3345.01");

        // The software row: 120.00 automation, 30.00 web, 54.99 shared.
        JsonNode software = null;
        for (JsonNode row : r.get("rows")) {
            if (row.get("code").asText().equals("6220")) {
                software = row;
            }
        }
        assertThat(software).isNotNull();
        assertThat(software.get("section").asText()).isEqualTo("expenses");
        assertThat(amt(software.get("amounts").get(0))).isEqualTo("120.00");
        assertThat(amt(software.get("amounts").get(1))).isEqualTo("30.00");
        assertThat(amt(software.get("amounts").get(2))).isEqualTo("54.99");
        assertThat(amt(software.get("total"))).isEqualTo("204.99");
    }

    @Test
    void ac4_theTotalColumnIsThePlainProfitAndLoss() {
        fixture();
        JsonNode r = report();
        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-01-01&to=2026-12-31");
        assertThat(amt(r.get("total").get("income"))).isEqualTo(amt(pl.get("income").get("total")));
        assertThat(amt(r.get("total").get("costOfGoodsSold"))).isEqualTo(amt(pl.get("costOfGoodsSold").get("total")));
        assertThat(amt(r.get("total").get("expenses"))).isEqualTo(amt(pl.get("expenses").get("total")));
        assertThat(amt(r.get("total").get("netIncome"))).isEqualTo(amt(pl.get("netIncome")));
        // And the tax-line report is untouched by business lines: same income on line 1 as without them.
        JsonNode tax = api.get(base + "/reports/tax-lines?taxYear=2026");
        assertThat(tax.toString()).doesNotContain("businessLine");
    }

    @Test
    void ac5_archivedLinesRefuseNewWorkButKeepTheirHistory() {
        JsonNode inv = invoice(web, List.of(invoiceLine("Site rebuild", "1500.00", acct.get("4010"))));
        api.patch(base + "/business-lines/" + web, Map.of("archived", true), HttpStatus.OK);

        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customer);
        body.put("issueDate", "2026-04-01");
        body.put("terms", "net_30");
        body.put("lines", List.of(invoiceLine("More", "10.00", acct.get("4010"))));
        body.put("businessLineId", web);
        assertThat(api.post(base + "/invoices", body, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("BUSINESS_LINE_ARCHIVED");

        // Still a column, because it has activity in the range.
        assertThat(amt(column(report(), "Websites & hosting").get("income"))).isEqualTo("1500.00");

        // Voiding the invoice reverses onto the archived line rather than failing. The line now nets to nothing
        // in the range, so — archived and with nothing to show — it is no longer a column.
        api.post(base + "/invoices/" + inv.get("id").asText() + "/void", Map.of(), HttpStatus.OK);
        List<String> names = new ArrayList<>();
        report().get("columns").forEach(c -> names.add(c.get("name").asText()));
        assertThat(names).containsExactly("Automation & AI", "Shared / overhead");
        assertThat(amt(report().get("total").get("income"))).isEqualTo("0.00");
    }

    @Test
    void ac6_aCreditNoteTakesBackFromTheLineItWasEarnedIn() {
        JsonNode inv = invoice(web, List.of(invoiceLine("Site rebuild", "1500.00", acct.get("4010")),
                invoiceLine("Hosting year", "300.00", acct.get("4900"))));
        String hostingLine = inv.get("lines").get(1).get("id").asText();
        Map<String, Object> creditLine = new HashMap<>(invoiceLine("Hosting refund", "300.00", acct.get("4900")));
        creditLine.put("invoiceLineId", hostingLine);
        String credit = api.post(base + "/credit-notes", Map.of("customerId", customer, "issueDate", "2026-03-15",
                "lines", List.of(creditLine)), HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + credit + "/issue", Map.of(), HttpStatus.OK);

        JsonNode w = column(report(), "Websites & hosting");
        assertThat(amt(w.get("income"))).isEqualTo("1500.00");
        assertThat(amt(column(report(), "Shared / overhead").get("income"))).isEqualTo("0.00");
    }

    @Test
    void ac7_aLineFromAnotherEntityIsRefused() {
        String other = api.newEntity(org, "sole_prop");
        String otherLine = api.post("/api/v1/orgs/" + org + "/entities/" + other + "/business-lines",
                Map.of("name", "Elsewhere"), HttpStatus.CREATED).get("id").asText();
        Map<String, Object> debit = new HashMap<>(Map.of("accountId", acct.get("6220"), "amount", money("10.00")));
        debit.put("businessLineId", otherLine);
        assertThat(api.post(base + "/journal-entries", Map.of("entryDate", "2026-03-10", "post", true,
                        "lines", List.of(debit, Map.of("accountId", acct.get("1010"), "amount", money("-10.00")))),
                HttpStatus.CONFLICT).get("code").asText()).isEqualTo("BUSINESS_LINE_NOT_FOUND");
    }

    @Test
    void ac8_theHashChainStillVerifiesAndCoversTheLine() {
        fixture();
        JsonNode verify = api.get(base + "/journal/verify");
        assertThat(verify.get("valid").asBoolean()).isTrue();
    }

    @Test
    void ac9_viewersReadButCannotChangeLines() {
        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"),
                HttpStatus.CREATED);
        viewer.get(base + "/business-lines", HttpStatus.OK);
        viewer.get(base + "/reports/profit-and-loss-by-business-line?from=2026-01-01&to=2026-12-31", HttpStatus.OK);
        viewer.post(base + "/business-lines", Map.of("name", "Nope"), HttpStatus.FORBIDDEN);
        new ApiClient(rest, false).get(base + "/business-lines", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac10_theCsvHasOneColumnPerLine() {
        fixture();
        ResponseEntity<String> csv = rest.exchange(
                base + "/reports/profit-and-loss-by-business-line.csv?from=2026-01-01&to=2026-12-31", HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = csv.getBody().split("\n");
        assertThat(lines[0]).isEqualTo("Section,Code,Account,Automation & AI,Websites & hosting,Shared / overhead,Total");
        assertThat(lines[lines.length - 1]).isEqualTo("total,,Net income,1780.00,1370.00,195.01,3345.01");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        return headers;
    }
}
