package com.aesoftwaresolutions.solid.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

/** Spec 032. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StatementApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    String customerId;
    Map<String, String> acct = new HashMap<>();

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
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private String invoice(String issueDate, String amount, boolean issue) {
        String id = api.post(base + "/invoices", Map.of(
                "customerId", customerId, "issueDate", issueDate, "terms", "net_30",
                "lines", List.of(Map.of("description", "Work", "quantity", "1", "unitPrice", money(amount),
                        "incomeAccountId", acct.get("4010")))), HttpStatus.CREATED).get("id").asText();
        if (issue) {
            api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
        }
        return id;
    }

    private void pay(String receivedDate, Map<String, String> amountsByInvoice) {
        List<Map<String, Object>> applications = new ArrayList<>();
        amountsByInvoice.forEach((invoiceId, amount) ->
                applications.add(Map.of("invoiceId", invoiceId, "amount", money(amount))));
        api.post(base + "/payments", Map.of(
                "customerId", customerId, "receivedDate", receivedDate,
                "depositAccountId", acct.get("1010"), "reference", "CHK-" + receivedDate,
                "applications", applications), HttpStatus.CREATED);
    }

    private JsonNode statement(String from, String to) {
        return api.get(base + "/customers/" + customerId + "/statement?from=" + from + "&to=" + to);
    }

    private static String amt(JsonNode node) {
        return node.get("amount").asText();
    }

    @Test
    void ac1_ac2_ac3_ac4_theStatementAddsUp() {
        String january = invoice("2026-01-10", "1000.00", true);
        pay("2026-01-20", Map.of(january, "400.00"));

        String marchA = invoice("2026-03-05", "500.00", true);
        String marchB = invoice("2026-03-06", "250.00", true);
        invoice("2026-03-07", "999.00", false);                       // draft: never sent
        String voided = invoice("2026-03-08", "111.00", true);
        api.post(base + "/invoices/" + voided + "/void", Map.of(), HttpStatus.OK);
        pay("2026-03-20", Map.of(marchA, "500.00", marchB, "100.00")); // one payment, two invoices

        JsonNode statement = statement("2026-03-01", "2026-03-31");

        assertThat(amt(statement.get("openingBalance"))).as("January's unpaid 600").isEqualTo("600.00");
        List<String> references = new ArrayList<>();
        statement.get("lines").forEach(line -> references.add(line.get("reference").asText()));
        assertThat(statement.get("lines")).hasSize(3);

        JsonNode first = statement.get("lines").get(0);
        assertThat(first.get("type").asText()).isEqualTo("invoice");
        assertThat(amt(first.get("charge"))).isEqualTo("500.00");
        assertThat(amt(first.get("balance"))).isEqualTo("1100.00");

        JsonNode second = statement.get("lines").get(1);
        assertThat(amt(second.get("charge"))).isEqualTo("250.00");
        assertThat(amt(second.get("balance"))).isEqualTo("1350.00");

        JsonNode third = statement.get("lines").get(2);
        assertThat(third.get("type").asText()).isEqualTo("payment");
        assertThat(amt(third.get("payment"))).as("one line for both applications").isEqualTo("600.00");
        assertThat(amt(third.get("balance"))).isEqualTo("750.00");

        assertThat(amt(statement.get("closingBalance"))).isEqualTo("750.00");
        assertThat(statement.toString()).doesNotContain("999.00").doesNotContain("111.00");
    }

    @Test
    void ac5_thePdfSaysWhoWhenAndHowMuch() throws IOException {
        invoice("2026-02-01", "820.00", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<byte[]> response = rest.exchange(
                base + "/customers/" + customerId + "/statement.pdf?from=2026-01-01&to=2026-02-28",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        try (PDDocument document = Loader.loadPDF(response.getBody())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Northwind Traders")
                    .contains("2026-01-01")
                    .contains("2026-02-28")
                    .contains("Amount due")
                    .contains("820.00");
        }
    }

    @Test
    void ac6_badRangesAndOtherOrganizations() {
        api.get(base + "/customers/" + customerId + "/statement?from=2026-05-01&to=2026-01-01",
                HttpStatus.BAD_REQUEST);

        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/customers/" + customerId + "/statement", HttpStatus.NOT_FOUND);
        new ApiClient(rest, false).get(base + "/customers/" + customerId + "/statement", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void defaultRangeEndsToday() {
        invoice("2026-02-01", "100.00", true);
        JsonNode statement = api.get(base + "/customers/" + customerId + "/statement");
        assertThat(statement.get("to").asText()).isNotEmpty();
        assertThat(statement.get("from").asText()).isLessThan(statement.get("to").asText());
    }
}
