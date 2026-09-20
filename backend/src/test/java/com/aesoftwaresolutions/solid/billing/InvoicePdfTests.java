package com.aesoftwaresolutions.solid.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/** Spec 031. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InvoicePdfTests {

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
        customerId = api.post(base + "/customers",
                Map.of("name", "Northwind Traders", "billingAddress", "1 Example Way\nTestville, TS 00000"),
                HttpStatus.CREATED).get("id").asText();
    }

    private JsonNode invoice(String description, String quantity, String unitPrice) {
        return api.post(base + "/invoices", Map.of(
                "customerId", customerId,
                "issueDate", "2026-03-01",
                "terms", "net_30",
                "memo", "Thanks for your business",
                "lines", List.of(Map.of("description", description, "quantity", quantity,
                        "unitPrice", Map.of("amount", unitPrice, "currency", "USD"),
                        "incomeAccountId", acct.get("4010")))), HttpStatus.CREATED);
    }

    private ResponseEntity<byte[]> download(String invoiceId, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(base + "/invoices/" + invoiceId + "/pdf", HttpMethod.GET, new HttpEntity<>(headers),
                byte[].class);
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void ac1_ac2_ac3_theInvoiceIsARealPdfWithTheRightNumbers() throws IOException {
        String invoiceId = invoice("Website build", "1", "1500.00").get("id").asText();
        JsonNode issued = api.post(base + "/invoices/" + invoiceId + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/payments", Map.of(
                "customerId", customerId,
                "receivedDate", "2026-03-10",
                "depositAccountId", acct.get("1010"),
                "applications", List.of(Map.of("invoiceId", invoiceId,
                        "amount", Map.of("amount", "500.00", "currency", "USD")))), HttpStatus.CREATED);

        ResponseEntity<byte[]> response = download(invoiceId, api.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains(issued.get("invoiceNumber").asText());

        String text = textOf(response.getBody());
        assertThat(text).contains("Northwind Traders")
                .contains(issued.get("invoiceNumber").asText())
                .contains("Website build")
                .contains("1500.00")
                .contains("Amount due")
                .contains("1000.00")
                .contains("Thanks for your business");
    }

    @Test
    void ac4_draftsAndVoidedInvoicesSayWhatTheyAre() throws IOException {
        String draftId = invoice("Consulting", "2", "100.00").get("id").asText();
        String draftText = textOf(download(draftId, api.token()).getBody());
        assertThat(draftText).contains("DRAFT").contains("has not been issued");

        String voidedId = invoice("Consulting", "1", "50.00").get("id").asText();
        api.post(base + "/invoices/" + voidedId + "/finalize", Map.of(), HttpStatus.OK);
        api.post(base + "/invoices/" + voidedId + "/void", Map.of(), HttpStatus.OK);
        String voidText = textOf(download(voidedId, api.token()).getBody());
        assertThat(voidText).contains("VOID").contains("not payable");
    }

    @Test
    void aLongInvoiceKeepsEveryLineAndItsTotal() throws IOException {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            lines.add(Map.of("description", "Line item " + i, "quantity", "1",
                    "unitPrice", Map.of("amount", "10.00", "currency", "USD"),
                    "incomeAccountId", acct.get("4010")));
        }
        String invoiceId = api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", "2026-03-01",
                "terms", "net_30", "lines", lines), HttpStatus.CREATED).get("id").asText();

        String text = textOf(download(invoiceId, api.token()).getBody());
        assertThat(text).contains("Line item 1").contains("Line item 60")
                .as("the totals block must not fall off the page").contains("Amount due").contains("600.00");
    }

    @Test
    void ac5_accessIsScoped() {
        String invoiceId = invoice("Website build", "1", "1500.00").get("id").asText();

        ApiClient outsider = new ApiClient(rest);
        assertThat(download(invoiceId, outsider.token()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(download(invoiceId, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac6_aLongDescriptionIsCutRatherThanOverflowing() throws IOException {
        String longDescription = "Design, build, test and document the customer portal including single sign-on, "
                + "reporting and the data migration from the previous system";
        String invoiceId = invoice(longDescription, "1", "9000.00").get("id").asText();

        String text = textOf(download(invoiceId, api.token()).getBody());
        assertThat(text).contains("Design, build, test").contains("...");
        assertThat(text).doesNotContain(longDescription);
        assertThat(text).contains("9000.00");
    }
}
