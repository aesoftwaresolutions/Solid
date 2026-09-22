package com.aesoftwaresolutions.solid.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
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

/** Spec 053. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WhatsComingTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();
    String customerId;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers", Map.of("name", "Acme"), HttpStatus.CREATED).get("id").asText();
        // Some money in the bank to start from.
        post(today.minusDays(30).toString(), "1010", "3010", "5000.00");
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private void post(String date, String debit, String credit, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", true);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debit), "amount", money(amount)),
                Map.of("accountId", acct.get(credit), "amount", money("-" + amount))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private String invoice(String issueDate, String terms, String amount, boolean finalize) {
        String id = api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", issueDate,
                        "terms", terms, "lines", List.of(Map.of("description", "Work", "quantity", "1",
                                "unitPrice", money(amount), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CREATED).get("id").asText();
        if (finalize) {
            api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
        }
        return id;
    }

    private JsonNode whatsComing() {
        return api.get(base + "/reports/whats-coming");
    }

    private static List<JsonNode> itemsOfKind(JsonNode report, String kind) {
        return java.util.stream.StreamSupport.stream(report.get("items").spliterator(), false)
                .filter(i -> i.get("kind").asText().equals(kind)).toList();
    }

    @Test
    void ac1_ac5_ac6_ac7_anIssuedInvoiceIsMoneyInOnItsDueDate() {
        invoice(today.toString(), "net_30", "1200.00", true);
        invoice(today.toString(), "net_30", "999.00", false);   // a draft is owed by nobody

        JsonNode report = whatsComing();

        List<JsonNode> due = itemsOfKind(report, "invoice_due");
        assertThat(due).hasSize(1);
        assertThat(due.get(0).get("date").asText()).isEqualTo(today.plusDays(30).toString());
        assertThat(due.get(0).get("amountIn").get("amount").asText()).isEqualTo("1200.00");

        // AC6: the running balance starts at today's cash.
        assertThat(report.get("openingCash").get("amount").asText()).isEqualTo("5000.00");
        assertThat(due.get(0).get("projectedBalance").get("amount").asText()).isEqualTo("6200.00");
        assertThat(report.get("totals").get("projectedClosing").get("amount").asText()).isEqualTo("6200.00");
        assertThat(report.toString()).doesNotContain("999.00");

        // AC7: it says what it is.
        assertThat(report.get("note").asText()).contains("not a forecast");
    }

    @Test
    void ac2_anOverdueBillIsListedTodayAndSaysSo() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Printer Co"), HttpStatus.CREATED)
                .get("id").asText();
        api.post(base + "/bills", Map.of("vendorId", vendorId, "billDate", today.minusDays(60).toString(),
                        "terms", "net_30", "vendorReference", "P-100",
                        "lines", List.of(Map.of("description", "Printing", "amount", money("300.00"),
                                "expenseAccountId", acct.get("6220")))),
                HttpStatus.CREATED);

        JsonNode report = whatsComing();

        List<JsonNode> bills = itemsOfKind(report, "bill_due");
        assertThat(bills).hasSize(1);
        assertThat(bills.get(0).get("date").asText()).isEqualTo(today.toString());
        assertThat(bills.get(0).get("description").asText()).contains("Overdue since");
        assertThat(bills.get(0).get("amountOut").get("amount").asText()).isEqualTo("300.00");
        assertThat(report.get("totals").get("projectedClosing").get("amount").asText()).isEqualTo("4700.00");
    }

    @Test
    void ac3_aMonthlyRecurringInvoiceShowsEachMonth() {
        api.post(base + "/recurring-invoices", Map.of("customerId", customerId, "name", "Retainer",
                        "terms", "net_30", "frequency", "monthly", "startDate", today.toString(),
                        "lines", List.of(Map.of("description", "Retainer", "quantity", "1",
                                "unitPrice", money("500.00"), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CREATED);

        List<JsonNode> upcoming = itemsOfKind(whatsComing(), "recurring_invoice");

        // Ninety days out: today's, and the next two months.
        assertThat(upcoming).hasSizeBetween(3, 4);
        assertThat(upcoming.get(0).get("amountIn").get("amount").asText()).isEqualTo("500.00");
        assertThat(upcoming.get(0).get("description").asText()).contains("Retainer").contains("Acme");
    }

    @Test
    void ac4_aRecurringEntryThatMovesCashIsListedAndOneThatDoesNotIsNot() {
        // Rent paid from the bank: money out.
        api.post(base + "/recurring-entries", Map.of("name", "Rent", "frequency", "monthly",
                        "startDate", today.toString(), "lines", List.of(
                                Map.of("accountId", acct.get("6200"), "amount", money("800.00")),
                                Map.of("accountId", acct.get("1010"), "amount", money("-800.00")))),
                HttpStatus.CREATED);
        // Depreciation: real, but it moves no money.
        api.post(base + "/recurring-entries", Map.of("name", "Depreciation", "frequency", "monthly",
                        "startDate", today.toString(), "lines", List.of(
                                Map.of("accountId", acct.get("6050"), "amount", money("100.00")),
                                Map.of("accountId", acct.get("1510"), "amount", money("-100.00")))),
                HttpStatus.CREATED);

        JsonNode report = whatsComing();

        List<JsonNode> entries = itemsOfKind(report, "recurring_entry");
        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(item -> assertThat(item.get("description").asText()).isEqualTo("Rent"));
        assertThat(entries.get(0).get("amountOut").get("amount").asText()).isEqualTo("800.00");
        assertThat(report.toString()).doesNotContain("Depreciation");
    }

    @Test
    void ac6_theLowestPointIsTheWorstDayTheArithmeticReaches() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Printer Co"), HttpStatus.CREATED)
                .get("id").asText();
        api.post(base + "/bills", Map.of("vendorId", vendorId, "billDate", today.toString(),
                        "terms", "net_15", "vendorReference", "BIG",
                        "lines", List.of(Map.of("description", "A large bill", "amount", money("4500.00"),
                                "expenseAccountId", acct.get("6220")))),
                HttpStatus.CREATED);
        invoice(today.toString(), "net_60", "3000.00", true);

        JsonNode report = whatsComing();

        // Down to 500 when the bill falls due, back up when the invoice is paid.
        assertThat(report.get("lowestPoint").get("projectedBalance").get("amount").asText()).isEqualTo("500.00");
        assertThat(report.get("lowestPoint").get("date").asText()).isEqualTo(today.plusDays(15).toString());
        assertThat(report.get("totals").get("projectedClosing").get("amount").asText()).isEqualTo("3500.00");
    }

    @Test
    void ac5_aDeactivatedTemplateIsNotListed() {
        String id = api.post(base + "/recurring-invoices", Map.of("customerId", customerId, "name", "Old deal",
                        "terms", "net_30", "frequency", "monthly", "startDate", today.toString(),
                        "lines", List.of(Map.of("description", "Retainer", "quantity", "1",
                                "unitPrice", money("500.00"), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CREATED).get("id").asText();
        api.post(base + "/recurring-invoices/" + id + "/deactivate", Map.of(), HttpStatus.OK);

        assertThat(itemsOfKind(whatsComing(), "recurring_invoice")).isEmpty();
    }

    @Test
    void ac8_anotherOrganizationSeesNothing() {
        new ApiClient(rest).get(base + "/reports/whats-coming", HttpStatus.NOT_FOUND);
        api.get(base + "/reports/whats-coming?from=2026-12-31&to=2026-01-01", HttpStatus.BAD_REQUEST);
    }
}
