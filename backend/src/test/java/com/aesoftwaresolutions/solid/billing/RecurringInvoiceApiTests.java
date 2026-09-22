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

/** Spec 052. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RecurringInvoiceApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();
    String customerId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers", Map.of("name", "Retainer Client"), HttpStatus.CREATED)
                .get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private JsonNode template(String startDate, Integer dayOfMonth, String frequency) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("name", "Monthly retainer");
        body.put("terms", "net_30");
        body.put("frequency", frequency);
        body.put("startDate", startDate);
        body.put("dayOfMonth", dayOfMonth);
        body.put("memo", "Retainer for the month");
        body.put("lines", List.of(Map.of("description", "Retainer", "quantity", "1",
                "unitPrice", money("1500.00"), "incomeAccountId", acct.get("4010"))));
        return api.post(base + "/recurring-invoices", body, HttpStatus.CREATED);
    }

    private JsonNode run(String through) {
        return api.post(base + "/recurring-invoices/run?through=" + through, Map.of(), HttpStatus.OK);
    }

    @Test
    void ac1_ac2_ac3_aMonthlyTemplateBillsEachMonthOnceAndOnlyOnce() {
        template("2026-01-10", 10, "monthly");

        JsonNode first = run("2026-03-31");
        assertThat(first.get("created")).hasSize(3);
        assertThat(api.get(base + "/invoices")).hasSize(3);
        assertThat(first.get("created").get(0).get("date").asText()).isEqualTo("2026-01-10");

        // AC2: running again changes nothing.
        assertThat(run("2026-03-31").get("created")).isEmpty();
        assertThat(api.get(base + "/invoices")).hasSize(3);

        // AC3: only the months in between.
        JsonNode later = run("2026-05-31");
        assertThat(later.get("created")).hasSize(2);
        assertThat(api.get(base + "/invoices")).hasSize(5);
    }

    @Test
    void ac1_ac4_theInvoicesAreDraftsWithTheTemplatesLinesAndTotals() {
        template("2026-02-01", 1, "monthly");
        run("2026-02-28");

        JsonNode invoice = api.get(base + "/invoices").get(0);
        assertThat(invoice.get("status").asText()).isEqualTo("draft");
        assertThat(invoice.get("total").get("amount").asText()).isEqualTo("1500.00");
        assertThat(invoice.get("lines").get(0).get("description").asText()).isEqualTo("Retainer");
        assertThat(invoice.get("memo").asText()).isEqualTo("Retainer for the month");

        // A draft is owed by nobody yet.
        assertThat(api.get(base + "/reports/accounts-receivable-aging?asOf=2026-03-31")
                .get("totals").get("total").get("amount").asText()).isEqualTo("0.00");

        api.post(base + "/invoices/" + invoice.get("id").asText() + "/finalize", Map.of(), HttpStatus.OK);
        assertThat(api.get(base + "/reports/accounts-receivable-aging?asOf=2026-03-31")
                .get("totals").get("total").get("amount").asText()).isEqualTo("1500.00");
    }

    @Test
    void ac5_deactivatingStopsFutureMonthsAndKeepsWhatWasCreated() {
        String id = template("2026-01-15", 15, "monthly").get("id").asText();
        run("2026-02-28");
        assertThat(api.get(base + "/invoices")).hasSize(2);

        api.post(base + "/recurring-invoices/" + id + "/deactivate", Map.of(), HttpStatus.OK);

        assertThat(run("2026-06-30").get("created")).isEmpty();
        assertThat(api.get(base + "/invoices")).hasSize(2);
    }

    @Test
    void ac6_anArchivedCustomerIsSkippedWithAReasonAndTheOthersStillRun() {
        template("2026-01-05", 5, "monthly");
        String otherCustomer = api.post(base + "/customers", Map.of("name", "Still Trading"), HttpStatus.CREATED)
                .get("id").asText();
        Map<String, Object> second = new HashMap<>();
        second.put("customerId", otherCustomer);
        second.put("name", "Another retainer");
        second.put("terms", "net_30");
        second.put("frequency", "monthly");
        second.put("startDate", "2026-01-05");
        second.put("lines", List.of(Map.of("description", "Support", "quantity", "1",
                "unitPrice", money("200.00"), "incomeAccountId", acct.get("4010"))));
        api.post(base + "/recurring-invoices", second, HttpStatus.CREATED);

        api.patch(base + "/customers/" + customerId, Map.of("archived", true), HttpStatus.OK);

        JsonNode result = run("2026-01-31");

        assertThat(result.get("skipped")).hasSize(1);
        assertThat(result.get("skipped").get(0).get("reason").asText().toLowerCase()).contains("archiv");
        assertThat(result.get("created")).hasSize(1);
    }

    @Test
    void ac7_theThirtyFirstBecomesTheThirtiethInApril() {
        template("2026-03-31", 31, "monthly");

        JsonNode result = run("2026-04-30");

        List<String> dates = java.util.stream.StreamSupport.stream(result.get("created").spliterator(), false)
                .map(c -> c.get("date").asText()).toList();
        assertThat(dates).containsExactly("2026-03-31", "2026-04-30");
    }

    @Test
    void quarterlyAndAnnualTemplatesStepTheRightWay() {
        template("2026-01-31", 31, "quarterly");

        List<String> dates = java.util.stream.StreamSupport
                .stream(run("2026-12-31").get("created").spliterator(), false)
                .map(c -> c.get("date").asText()).toList();

        assertThat(dates).containsExactly("2026-01-31", "2026-04-30", "2026-07-31", "2026-10-31");
    }

    @Test
    void ac8_anotherOrganizationSeesNothing() {
        template("2026-01-10", 10, "monthly");

        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/recurring-invoices", HttpStatus.NOT_FOUND);
        outsider.post(base + "/recurring-invoices/run?through=2026-12-31", Map.of(), HttpStatus.NOT_FOUND);
    }
}
