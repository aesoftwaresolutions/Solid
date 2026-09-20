package com.aesoftwaresolutions.solid.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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

/** Spec 036. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class YearEndChecklistTests {

    private static final int YEAR = 2026;

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

    private JsonNode checklist() {
        return api.get(base + "/reports/year-end-checklist?taxYear=" + YEAR);
    }

    private static JsonNode item(JsonNode checklist, String key) {
        for (JsonNode node : checklist.get("items")) {
            if (node.get("key").asText().equals(key)) {
                return node;
            }
        }
        throw new AssertionError("No item " + key + " in " + checklist);
    }

    private static String status(JsonNode checklist, String key) {
        return item(checklist, key).get("status").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private String postEntry(String date, boolean post) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", post);
        body.put("lines", List.of(
                Map.of("accountId", acct.get("6010"), "amount", money("100.00")),
                Map.of("accountId", acct.get("1010"), "amount", money("-100.00"))));
        return api.post(base + "/journal-entries", body, HttpStatus.CREATED).get("id").asText();
    }

    @Test
    void ac1_ac7_anEmptyYearIsNotReadyAndClosingTheBooksFinishesIt() {
        JsonNode start = checklist();
        assertThat(start.get("ready").asBoolean()).isFalse();
        assertThat(status(start, "books_closed")).isEqualTo("todo");
        assertThat(item(start, "books_closed").get("detail").asText()).contains("2026-12-31");
        assertThat(status(start, "bank_categorized")).isEqualTo("done");
        assertThat(status(start, "reconciled")).as("no bank accounts, nothing to reconcile").isEqualTo("done");
        assertThat(status(start, "depreciation")).isEqualTo("done");
        assertThat(status(start, "vendors_1099")).isEqualTo("done");

        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-12-31"));

        JsonNode closed = checklist();
        assertThat(status(closed, "books_closed")).isEqualTo("done");
        assertThat(closed.get("ready").asBoolean()).as("unknown items do not block ready").isTrue();
    }

    @Test
    void ac2_ac4_uncategorizedActivityAndUnreconciledAccounts() {
        String bankAccount = api.post(base + "/bank-accounts",
                Map.of("name", "Checking", "glAccountId", acct.get("1010")), HttpStatus.CREATED).get("id").asText();
        api.postFile(base + "/bank-accounts/" + bankAccount + "/imports", "statement.csv",
                "Date,Description,Amount\n2026-03-01,GODADDY.COM,-19.99\n".getBytes(StandardCharsets.UTF_8),
                HttpStatus.CREATED);

        JsonNode withWork = checklist();
        assertThat(status(withWork, "bank_categorized")).isEqualTo("todo");
        assertThat(item(withWork, "bank_categorized").get("count").asInt()).isEqualTo(1);
        assertThat(item(withWork, "bank_categorized").get("where").asText()).isEqualTo("bank");
        assertThat(status(withWork, "reconciled")).isEqualTo("todo");
        assertThat(item(withWork, "reconciled").get("detail").asText()).contains("Checking");

        String txnId = api.get(base + "/bank-transactions?status=new").get(0).get("id").asText();
        api.post(base + "/bank-transactions/" + txnId + "/categorize", Map.of("accountId", acct.get("6220")),
                HttpStatus.OK);
        assertThat(status(checklist(), "bank_categorized")).isEqualTo("done");

        // Reconcile it through the year end.
        String reconciliations = base + "/bank-accounts/" + bankAccount + "/reconciliations";
        String reconciliationId = api.post(reconciliations,
                Map.of("statementDate", "2026-12-31", "statementEndingBalance", money("-19.99")),
                HttpStatus.CREATED).get("id").asText();
        JsonNode candidates = api.get(reconciliations + "/" + reconciliationId + "/candidates");
        List<String> lineIds = new java.util.ArrayList<>();
        candidates.forEach(c -> lineIds.add(c.get("lineId").asText()));
        api.post(reconciliations + "/" + reconciliationId + "/cleared",
                Map.of("lineIds", lineIds, "cleared", true), HttpStatus.OK);
        api.post(reconciliations + "/" + reconciliationId + "/complete", Map.of(), HttpStatus.OK);

        assertThat(status(checklist(), "reconciled")).isEqualTo("done");
    }

    @Test
    void ac3_draftEntriesAreListedUntilTheyArePosted() {
        String draft = postEntry("2026-05-01", false);
        JsonNode withDraft = checklist();
        assertThat(status(withDraft, "no_drafts")).isEqualTo("todo");
        assertThat(item(withDraft, "no_drafts").get("count").asInt()).isEqualTo(1);

        api.post(base + "/journal-entries/" + draft + "/post", Map.of(), HttpStatus.OK);
        assertThat(status(checklist(), "no_drafts")).isEqualTo("done");
    }

    @Test
    void ac5_unpostedDepreciationIsListed() {
        api.post(base + "/assets", Map.of(
                "name", "Laptop", "placedInServiceDate", "2026-01-01", "cost", money("1200.00"),
                "usefulLifeMonths", 36, "assetAccountId", acct.get("1500"),
                "accumulatedAccountId", acct.get("1510"),
                "depreciationExpenseAccountId", acct.get("6050")), HttpStatus.CREATED);

        JsonNode before = checklist();
        assertThat(status(before, "depreciation")).isEqualTo("todo");
        assertThat(item(before, "depreciation").get("count").asInt()).isEqualTo(12);

        api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-12"), HttpStatus.CREATED);
        assertThat(status(checklist(), "depreciation")).isEqualTo("done");
    }

    @Test
    void ac6_a1099VendorMissingDetailsIsNamed() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Contractor Co", "is1099Vendor", true),
                HttpStatus.CREATED).get("id").asText();
        String billId = api.post(base + "/bills", Map.of(
                "vendorId", vendorId, "billDate", "2026-04-01", "terms", "net_30",
                "lines", List.of(Map.of("description", "Work", "amount", money("3000.00"),
                        "expenseAccountId", acct.get("6010")))), HttpStatus.CREATED).get("id").asText();
        api.post(base + "/bills/" + billId + "/approve", Map.of(), HttpStatus.OK);
        api.post(base + "/bill-payments", Map.of(
                "vendorId", vendorId, "paidDate", "2026-04-15", "paymentAccountId", acct.get("1010"),
                "applications", List.of(Map.of("billId", billId, "amount", money("3000.00")))),
                HttpStatus.CREATED);

        JsonNode withVendor = checklist();
        assertThat(status(withVendor, "vendors_1099")).isEqualTo("todo");
        assertThat(item(withVendor, "vendors_1099").get("detail").asText()).contains("Contractor Co");
    }

    @Test
    void ac8_aYearWithNoRuleCoverageIsUnknownNotTodo() {
        JsonNode far = api.get(base + "/reports/year-end-checklist?taxYear=2099");
        JsonNode figures = item(far, "tax_figures");

        assertThat(figures.get("status").asText()).isEqualTo("unknown");
        assertThat(figures.get("detail").asText()).contains("does not stop you closing the books");
        assertThat(far.get("items")).hasSize(8);
    }

    @Test
    void anotherOrganizationCannotSeeTheChecklist() {
        new ApiClient(rest).get(base + "/reports/year-end-checklist?taxYear=" + YEAR, HttpStatus.NOT_FOUND);
    }
}
