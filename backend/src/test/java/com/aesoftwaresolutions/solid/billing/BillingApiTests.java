package com.aesoftwaresolutions.solid.billing;

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

/** Spec 012. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BillingApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    String customer;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customer = api.post(base + "/customers", Map.of("name", "Acme Client LLC", "email", "ap@acme.test"),
                HttpStatus.CREATED).get("id").asText();
    }

    private static Map<String, Object> line(String description, String quantity, String unitPrice, String accountId) {
        return Map.of("description", description, "quantity", quantity,
                "unitPrice", Map.of("amount", unitPrice, "currency", "USD"), "incomeAccountId", accountId);
    }

    private JsonNode draft(String issueDate, String terms, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customer);
        body.put("issueDate", issueDate);
        body.put("terms", terms);
        body.put("lines", lines);
        return api.post(base + "/invoices", body, HttpStatus.CREATED);
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    @Test
    void ac1_ac2_ac3_computesLineAmountsNumbersAndDueDates() {
        JsonNode invoice = draft("2026-09-01", "net_30", List.of(
                line("Design work", "2.5", "40.00", acct.get("4010")),
                line("Hosting", "3", "19.99", acct.get("4900"))));

        assertThat(invoice.get("invoiceNumber").asText()).isEqualTo("INV-0001");
        assertThat(invoice.get("dueDate").asText()).isEqualTo("2026-10-01");
        assertThat(amt(invoice.get("lines").get(0).get("amount"))).isEqualTo("100.00");
        assertThat(amt(invoice.get("lines").get(1).get("amount"))).isEqualTo("59.97");
        assertThat(amt(invoice.get("total"))).isEqualTo("159.97");
        assertThat(amt(invoice.get("balanceDue"))).isEqualTo("159.97");
        assertThat(invoice.get("status").asText()).isEqualTo("draft");

        JsonNode second = draft("2026-09-02", "due_on_receipt", List.of(line("Consult", "1", "250.00", acct.get("4010"))));
        assertThat(second.get("invoiceNumber").asText()).isEqualTo("INV-0002");
        assertThat(second.get("dueDate").asText()).isEqualTo("2026-09-02");

        Map<String, Object> explicit = new HashMap<>();
        explicit.put("customerId", customer);
        explicit.put("issueDate", "2026-09-03");
        explicit.put("terms", "net_15");
        explicit.put("invoiceNumber", "INV-0001");
        explicit.put("lines", List.of(line("Dup", "1", "10.00", acct.get("4010"))));
        assertThat(api.post(base + "/invoices", explicit, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("INVOICE_NUMBER_TAKEN");
    }

    @Test
    void ac4_ac5_finalizePostsBalancedEntry() {
        JsonNode invoice = draft("2026-09-01", "net_30", List.of(
                line("Design work", "2.5", "40.00", acct.get("4010")),
                line("Other", "1", "59.97", acct.get("4900"))));
        String id = invoice.get("id").asText();

        JsonNode finalized = api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
        assertThat(finalized.get("status").asText()).isEqualTo("open");

        JsonNode entry = api.get(base + "/journal-entries/" + finalized.get("journalEntryId").asText());
        assertThat(entry.get("source").asText()).isEqualTo("invoice");
        assertThat(entry.get("lines")).hasSize(3);
        assertThat(entry.get("lines").get(0).get("accountId").asText()).isEqualTo(acct.get("1100")); // A/R
        assertThat(amt(entry.get("lines").get(0).get("amount"))).isEqualTo("159.97");
        assertThat(amt(entry.get("lines").get(1).get("amount"))).isEqualTo("-100.00");

        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-12-31");
        assertThat(tb.get("totalDebit")).isEqualTo(tb.get("totalCredit"));

        // editing after finalize is refused
        Map<String, Object> update = new HashMap<>();
        update.put("customerId", customer);
        update.put("issueDate", "2026-09-01");
        update.put("terms", "net_30");
        update.put("lines", List.of(line("Changed", "1", "1.00", acct.get("4010"))));
        assertThat(api.patch(base + "/invoices/" + id, update, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("INVOICE_NOT_DRAFT");
        api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.CONFLICT);
    }

    @Test
    void ac5_linesMustUseIncomeAccounts() {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customer);
        body.put("issueDate", "2026-09-01");
        body.put("terms", "net_30");
        body.put("lines", List.of(line("Wrong", "1", "10.00", acct.get("6010"))));
        assertThat(api.post(base + "/invoices", body, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("ACCOUNT_NOT_POSTABLE");

        body.put("lines", List.of(line("Zero", "0", "10.00", acct.get("4010"))));
        api.post(base + "/invoices", body, HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac6_paymentsApplyToInvoicesAndUpdateStatus() {
        String first = draft("2026-09-01", "net_30", List.of(line("Work", "1", "100.00", acct.get("4010")))).get("id").asText();
        String second = draft("2026-09-02", "net_30", List.of(line("Work", "1", "250.00", acct.get("4010")))).get("id").asText();
        api.post(base + "/invoices/" + first + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/invoices/" + second + "/finalize", Map.of(), HttpStatus.OK);

        JsonNode payment = api.post(base + "/payments", Map.of(
                "customerId", customer, "receivedDate", "2026-09-15", "depositAccountId", acct.get("1010"),
                "method", "ach", "applications", List.of(
                        Map.of("invoiceId", first, "amount", Map.of("amount", "100.00", "currency", "USD")),
                        Map.of("invoiceId", second, "amount", Map.of("amount", "50.00", "currency", "USD")))),
                HttpStatus.CREATED);
        assertThat(amt(payment.get("amount"))).isEqualTo("150.00");

        assertThat(api.get(base + "/invoices/" + first).get("status").asText()).isEqualTo("paid");
        JsonNode partial = api.get(base + "/invoices/" + second);
        assertThat(partial.get("status").asText()).isEqualTo("partially_paid");
        assertThat(amt(partial.get("balanceDue"))).isEqualTo("200.00");

        JsonNode entry = api.get(base + "/journal-entries/" + payment.get("journalEntryId").asText());
        assertThat(entry.get("lines").get(0).get("accountId").asText()).isEqualTo(acct.get("1010"));
        assertThat(amt(entry.get("lines").get(0).get("amount"))).isEqualTo("150.00");

        JsonNode over = api.post(base + "/payments", Map.of(
                "customerId", customer, "receivedDate", "2026-09-16", "depositAccountId", acct.get("1010"),
                "applications", List.of(Map.of("invoiceId", second, "amount", Map.of("amount", "500.00", "currency", "USD")))),
                HttpStatus.CONFLICT);
        assertThat(over.get("code").asText()).isEqualTo("OVERPAYMENT");

        String otherCustomer = api.post(base + "/customers", Map.of("name", "Other Co"), HttpStatus.CREATED).get("id").asText();
        assertThat(api.post(base + "/payments", Map.of(
                "customerId", otherCustomer, "receivedDate", "2026-09-16", "depositAccountId", acct.get("1010"),
                "applications", List.of(Map.of("invoiceId", second, "amount", Map.of("amount", "10.00", "currency", "USD")))),
                HttpStatus.CONFLICT).get("code").asText()).isEqualTo("WRONG_CUSTOMER");
    }

    @Test
    void ac7_voidReversesOnlyWhenUnpaid() {
        String id = draft("2026-09-01", "net_30", List.of(line("Work", "1", "100.00", acct.get("4010")))).get("id").asText();
        api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/payments", Map.of("customerId", customer, "receivedDate", "2026-09-10",
                        "depositAccountId", acct.get("1010"),
                        "applications", List.of(Map.of("invoiceId", id, "amount", Map.of("amount", "10.00", "currency", "USD")))),
                HttpStatus.CREATED);
        assertThat(api.post(base + "/invoices/" + id + "/void", Map.of(), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("INVOICE_HAS_PAYMENTS");

        String clean = draft("2026-09-05", "net_30", List.of(line("Work", "1", "75.00", acct.get("4010")))).get("id").asText();
        api.post(base + "/invoices/" + clean + "/finalize", Map.of(), HttpStatus.OK);
        JsonNode voided = api.post(base + "/invoices/" + clean + "/void", Map.of(), HttpStatus.OK);
        assertThat(voided.get("status").asText()).isEqualTo("void");

        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-09-01&to=2026-09-30");
        assertThat(amt(pl.get("netIncome"))).isEqualTo("100.00"); // 100 + 75 - 75 reversed
    }

    @Test
    void ac8_agingBucketsMatchTheReceivableBalance() {
        String current = draft("2026-09-20", "net_30", List.of(line("Recent", "1", "100.00", acct.get("4010")))).get("id").asText();
        String late = draft("2026-07-01", "net_15", List.of(line("Old", "1", "250.00", acct.get("4010")))).get("id").asText();
        api.post(base + "/invoices/" + current + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/invoices/" + late + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/payments", Map.of("customerId", customer, "receivedDate", "2026-09-25",
                        "depositAccountId", acct.get("1010"),
                        "applications", List.of(Map.of("invoiceId", late, "amount", Map.of("amount", "50.00", "currency", "USD")))),
                HttpStatus.CREATED);

        JsonNode aging = api.get(base + "/reports/accounts-receivable-aging?asOf=2026-09-30");
        JsonNode totals = aging.get("totals");
        assertThat(amt(totals.get("current"))).isEqualTo("100.00");   // due 2026-10-20
        assertThat(amt(totals.get("days61to90"))).isEqualTo("200.00"); // due 2026-07-16, 76 days late
        assertThat(amt(totals.get("total"))).isEqualTo("300.00");
        assertThat(aging.get("customers")).hasSize(1);

        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-09-30");
        String receivable = null;
        for (JsonNode row : tb.get("rows")) {
            if (row.get("code").asText().equals("1100")) {
                receivable = amt(row.get("debit"));
            }
        }
        assertThat(receivable).as("A/R balance equals total aging").isEqualTo("300.00");
    }

    @Test
    void ac9_ac10_lockedPeriodsAndViewerPermissions() {
        String id = draft("2026-05-01", "net_30", List.of(line("Work", "1", "100.00", acct.get("4010")))).get("id").asText();
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-06-30"));
        assertThat(api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("PERIOD_LOCKED");
        assertThat(api.get(base + "/invoices/" + id).get("status").asText()).isEqualTo("draft");

        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get(base + "/invoices", HttpStatus.OK);
        viewer.post(base + "/customers", Map.of("name", "Nope"), HttpStatus.FORBIDDEN);
    }
}
