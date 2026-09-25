package com.aesoftwaresolutions.solid.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** Spec 065 — the billing findings of the fourth review, rows 1, 3, 4, 5 and 12. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FourthReviewBillingTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();
    String customerId;
    String rateId;
    LocalDate today = LocalDate.now();
    ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers", Map.of("name", "Northwind Traders"), HttpStatus.CREATED)
                .get("id").asText();
        // A made-up jurisdiction and rate: these tests are about the arithmetic, not any real state's rate.
        rateId = api.post(base + "/sales-tax-rates", Map.of(
                "jurisdiction", "Testville", "ratePercent", "8.25",
                "liabilityAccountId", acct.get("2200"),
                "effectiveFrom", today.minusYears(2).toString(),
                "note", "Fixture rate for spec 065 — not a real rate"), HttpStatus.CREATED).get("id").asText();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private JsonNode taxedInvoice(String amount) {
        String id = api.post(base + "/invoices", Map.of(
                "customerId", customerId, "issueDate", today.toString(), "terms", "net_30",
                "lines", List.of(Map.of("description", "Widgets", "quantity", "1",
                        "unitPrice", money(amount), "incomeAccountId", acct.get("4010"),
                        "taxRateId", rateId))), HttpStatus.CREATED).get("id").asText();
        return api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
    }

    private JsonNode credit(String amount, String invoiceLineId) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", "Returned widgets");
        line.put("quantity", "1");
        line.put("unitPrice", money(amount));
        line.put("incomeAccountId", acct.get("4010"));
        line.put("invoiceLineId", invoiceLineId);
        return api.post(base + "/credit-notes", Map.of("customerId", customerId, "issueDate", today.toString(),
                "lines", List.of(line)), HttpStatus.CREATED);
    }

    private JsonNode issue(JsonNode credit) {
        return api.post(base + "/credit-notes/" + credit.get("id").asText() + "/issue", Map.of(), HttpStatus.OK);
    }

    private String balance(String section, String code) {
        JsonNode sheet = api.get(base + "/reports/balance-sheet?asOf=" + today.plusDays(1));
        for (JsonNode row : sheet.get(section).get("rows")) {
            if (row.get("code").asText().equals(code)) {
                return row.get("amount").get("amount").asText();
            }
        }
        return "0.00";
    }

    private String taxOf(JsonNode credit) {
        return credit.get("lines").get(0).get("taxAmount").get("amount").asText();
    }

    // ---------------- row 1: void vs credit notes ----------------

    @Test
    void row1_anInvoiceWithAnIssuedCreditNoteAgainstItCannotBeVoided() {
        JsonNode invoice = taxedInvoice("1000.00");
        issue(credit("1000.00", invoice.get("lines").get(0).get("id").asText()));

        JsonNode refused = api.post(base + "/invoices/" + invoice.get("id").asText() + "/void", Map.of(),
                HttpStatus.CONFLICT);

        assertThat(refused.toString()).contains("INVOICE_HAS_CREDIT_NOTES");
        assertThat(balance("liabilities", "2200"))
                .as("the tax was reversed once, by the credit note, and not a second time").isEqualTo("0.00");
    }

    @Test
    void row1_aDraftCreditNoteAlsoStopsTheVoid() {
        JsonNode invoice = taxedInvoice("1000.00");
        credit("400.00", invoice.get("lines").get(0).get("id").asText());

        api.post(base + "/invoices/" + invoice.get("id").asText() + "/void", Map.of(), HttpStatus.CONFLICT);
    }

    // ---------------- row 3: a full credit split across notes ----------------

    @Test
    void row3_aFullCreditInTwoHalvesGivesBackAllTheTaxNotACentLess() {
        // 1.04 at 8.25% charges 0.09; each half at 8.25% rounds to 0.04, which would strand a cent.
        JsonNode invoice = taxedInvoice("1.04");
        String line = invoice.get("lines").get(0).get("id").asText();
        assertThat(balance("liabilities", "2200")).isEqualTo("0.09");

        assertThat(taxOf(issue(credit("0.52", line)))).isEqualTo("0.04");
        JsonNode last = issue(credit("0.52", line));

        assertThat(taxOf(last)).as("the note that finishes the line takes whatever tax is left").isEqualTo("0.05");
        assertThat(balance("liabilities", "2200")).as("net tax for a fully credited sale is zero")
                .isEqualTo("0.00");
    }

    @Test
    void row3_aFullCreditInTwoHalvesGivesBackAllTheTaxNotACentMore() {
        // 1.10 at 8.25% charges 0.09; each half rounds to 0.05, which would give back 0.10.
        JsonNode invoice = taxedInvoice("1.10");
        String line = invoice.get("lines").get(0).get("id").asText();

        issue(credit("0.55", line));
        JsonNode last = issue(credit("0.55", line));

        assertThat(taxOf(last)).isEqualTo("0.04");
        assertThat(balance("liabilities", "2200")).isEqualTo("0.00");
    }

    @Test
    void row3_partialCreditsNeverGiveBackMoreTaxThanWasCharged() {
        // 1.10 charged 0.09. Ten credits of 0.10 each round to 0.01 apiece (0.825 cents); after nine of them
        // the whole 0.09 is back, so the tenth — still partial — may give back nothing more.
        JsonNode invoice = taxedInvoice("1.10");
        String line = invoice.get("lines").get(0).get("id").asText();
        for (int i = 0; i < 9; i++) {
            issue(credit("0.10", line));
        }
        JsonNode tenth = issue(credit("0.10", line));

        assertThat(taxOf(tenth)).isEqualTo("0.00");
        assertThat(balance("liabilities", "2200")).as("never below zero").isEqualTo("0.00");
    }

    // ---------------- row 4: converting a quote twice at once ----------------

    @Test
    void row4_twoConversionsAtOnceMakeOneInvoice() throws Exception {
        for (int round = 0; round < 5; round++) {
            Map<String, Object> body = new HashMap<>();
            body.put("customerId", customerId);
            body.put("issueDate", today.toString());
            body.put("validUntil", today.plusDays(30).toString());
            body.put("lines", List.of(Map.of("description", "Design", "quantity", "1",
                    "unitPrice", money("500.00"), "incomeAccountId", acct.get("4010"))));
            String quote = api.post(base + "/quotes", body, HttpStatus.CREATED).get("id").asText();
            api.post(base + "/quotes/" + quote + "/send", Map.of(), HttpStatus.OK);
            api.post(base + "/quotes/" + quote + "/accept", Map.of(), HttpStatus.OK);

            List<Integer> codes = together(() -> api.attempt(HttpMethod.POST,
                    base + "/quotes/" + quote + "/convert", Map.of()));

            assertThat(codes).as("round " + round + ": one conversion wins, the other is refused")
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(api.get(base + "/invoices")).as("five quotes, five invoices").hasSize(5);
    }

    // ---------------- row 5: overlapping recurring-invoice runs ----------------

    @Test
    void row5_twoRunsAtOnceBillEachMonthOnce() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("name", "Monthly retainer");
        body.put("terms", "net_30");
        body.put("frequency", "monthly");
        body.put("startDate", "2026-01-10");
        body.put("dayOfMonth", 10);
        body.put("lines", List.of(Map.of("description", "Retainer", "quantity", "1",
                "unitPrice", money("1500.00"), "incomeAccountId", acct.get("4010"))));
        api.post(base + "/recurring-invoices", body, HttpStatus.CREATED);

        List<Integer> codes = together(() -> api.attempt(HttpMethod.POST,
                base + "/recurring-invoices/run?through=2026-06-30", Map.of()));

        assertThat(codes).containsOnly(200);
        assertThat(api.get(base + "/invoices")).as("January to June, once each").hasSize(6);
    }

    // ---------------- row 12: a template that could never bill ----------------

    @Test
    void row12_aTemplateWithSomeoneElsesTaxRateIsRefusedWhenSaved() {
        ApiClient stranger = new ApiClient(rest);
        String theirOrg = stranger.newOrg();
        String theirEntity = stranger.newEntity(theirOrg, "sole_prop");
        String theirBase = "/api/v1/orgs/" + theirOrg + "/entities/" + theirEntity;
        Map<String, String> theirAccounts = new HashMap<>();
        stranger.post(theirBase + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> theirAccounts.put(a.get("code").asText(), a.get("id").asText()));
        String theirRate = stranger.post(theirBase + "/sales-tax-rates", Map.of(
                "jurisdiction", "Elsewhere", "ratePercent", "5.00",
                "liabilityAccountId", theirAccounts.get("2200"),
                "effectiveFrom", today.minusYears(1).toString(),
                "note", "Fixture rate for spec 065 — not a real rate"), HttpStatus.CREATED).get("id").asText();

        assertThat(saveTemplate(theirRate, acct.get("4010"))).isIn(400, 404, 409);
        assertThat(saveTemplate(UUID.randomUUID().toString(), acct.get("4010"))).isIn(400, 404, 409);
        assertThat(saveTemplate(null, acct.get("6010")))
                .as("an expense account is not somewhere a sale can be booked").isIn(400, 409);
        assertThat(saveTemplate(rateId, acct.get("4010"))).isEqualTo(201);
    }

    private int saveTemplate(String taxRateId, String incomeAccountId) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", "Retainer");
        line.put("quantity", "1");
        line.put("unitPrice", money("100.00"));
        line.put("incomeAccountId", incomeAccountId);
        line.put("taxRateId", taxRateId);
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("name", "Retainer");
        body.put("terms", "net_30");
        body.put("frequency", "monthly");
        body.put("startDate", "2026-01-10");
        body.put("lines", List.of(line));
        return api.attempt(HttpMethod.POST, base + "/recurring-invoices", body);
    }

    /** Runs the same call on two threads released at the same instant, and returns both results. */
    private List<Integer> together(Callable<Integer> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
