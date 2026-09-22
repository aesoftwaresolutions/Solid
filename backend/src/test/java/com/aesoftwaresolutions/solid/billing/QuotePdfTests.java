package com.aesoftwaresolutions.solid.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

/** Spec 055. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class QuotePdfTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    String customerId;
    Map<String, String> acct = new HashMap<>();
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers",
                Map.of("name", "Prospect Ltd", "billingAddress", "1 Example Way\nTestville, TS 00000"),
                HttpStatus.CREATED).get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private JsonNode quote(String amount, String validUntil) {
        return quote(amount, validUntil, today.toString());
    }

    private JsonNode quote(String amount, String validUntil, String issueDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("issueDate", issueDate);
        body.put("validUntil", validUntil);
        body.put("memo", "Happy to talk it through");
        body.put("lines", List.of(Map.of("description", "Design and build", "quantity", "1",
                "unitPrice", money(amount), "incomeAccountId", acct.get("4010"))));
        return api.post(base + "/quotes", body, HttpStatus.CREATED);
    }

    private ResponseEntity<byte[]> download(String quoteId, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(base + "/quotes/" + quoteId + "/pdf", HttpMethod.GET, new HttpEntity<>(headers),
                byte[].class);
    }

    private String textOf(String quoteId) throws IOException {
        return textOf(download(quoteId, api.token()).getBody());
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void ac1_ac3_theQuoteIsARealPdfWithTheRightNumbers() throws IOException {
        JsonNode created = quote("4000.00", today.plusDays(30).toString());
        String id = created.get("id").asText();
        api.post(base + "/quotes/" + id + "/send", Map.of(), HttpStatus.OK);

        ResponseEntity<byte[]> response = download(id, api.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("quote-" + created.get("quoteNumber").asText() + ".pdf");

        String text = textOf(response.getBody());
        assertThat(text).contains("QUOTE " + created.get("quoteNumber").asText())
                .contains("Prospect Ltd")
                .contains("Testville, TS 00000")
                .contains("Design and build")
                .contains("4000.00")
                .contains("Total")
                .contains("Happy to talk it through")
                // AC3
                .contains("This price holds until " + today.plusDays(30));
    }

    @Test
    void ac2_itCannotBeMistakenForABill() throws IOException {
        String id = quote("4000.00", null).get("id").asText();
        api.post(base + "/quotes/" + id + "/send", Map.of(), HttpStatus.OK);

        String text = textOf(id);
        assertThat(text).contains("This is a quote, not a bill.");
        assertThat(text.toLowerCase()).doesNotContain("invoice")
                .doesNotContain("amount due")
                .doesNotContain("due ")
                .doesNotContain("terms")
                .doesNotContain("pay ");
    }

    @Test
    void aDraftQuoteSaysSo() throws IOException {
        String id = quote("1200.00", null).get("id").asText();
        assertThat(textOf(id)).contains("draft quote").contains("has not been sent");
    }

    @Test
    void ac4_expiredDeclinedAndConvertedQuotesCarryTheirWatermark() throws IOException {
        String expired = quote("1000.00", today.minusDays(16).toString(), today.minusDays(30).toString())
                .get("id").asText();
        assertThat(textOf(expired)).contains("EXPIRED").contains("no longer held");

        String declined = quote("2000.00", null).get("id").asText();
        api.post(base + "/quotes/" + declined + "/send", Map.of(), HttpStatus.OK);
        api.post(base + "/quotes/" + declined + "/decline", Map.of("reason", "Went with someone cheaper"),
                HttpStatus.OK);
        assertThat(textOf(declined)).contains("DECLINED").contains("Went with someone cheaper");

        String converted = quote("3000.00", null).get("id").asText();
        api.post(base + "/quotes/" + converted + "/send", Map.of(), HttpStatus.OK);
        api.post(base + "/quotes/" + converted + "/accept", Map.of(), HttpStatus.OK);
        api.post(base + "/quotes/" + converted + "/convert", Map.of(), HttpStatus.CREATED);
        // The converted quote is the one place the word "invoice" belongs: it points at the document that is
        // actually payable, which here is still a draft without a number.
        assertThat(textOf(converted)).contains("INVOICED").contains("became invoice");
    }

    @Test
    void ac5_twoHundredLinesKeepEveryLineAndTheTotal() throws IOException {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            lines.add(Map.of("description", "Line item " + i, "quantity", "1",
                    "unitPrice", money("10.00"), "incomeAccountId", acct.get("4010")));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("issueDate", today.toString());
        body.put("lines", lines);
        String id = api.post(base + "/quotes", body, HttpStatus.CREATED).get("id").asText();

        String text = textOf(id);
        for (int i = 1; i <= 200; i++) {
            assertThat(text).as("line " + i + " must survive onto a continuation page").contains("Line item " + i);
        }
        assertThat(text).contains("2000.00");
    }

    @Test
    void ac6_accessIsScoped() {
        String id = quote("4000.00", null).get("id").asText();

        ApiClient outsider = new ApiClient(rest);
        assertThat(download(id, outsider.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(download(id, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
