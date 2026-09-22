package com.aesoftwaresolutions.solid.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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

/** Spec 056. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LetterheadApiTests {

    private static final byte[] PNG = png();

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String orgId;
    String base;
    String customerId;
    Map<String, String> acct = new HashMap<>();
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        orgId = api.newOrg();
        String entity = api.newEntity(orgId, "sole_prop");
        base = "/api/v1/orgs/" + orgId + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        customerId = api.post(base + "/customers", Map.of("name", "Northwind Traders"), HttpStatus.CREATED)
                .get("id").asText();
    }

    private static byte[] png() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> letterhead() {
        Map<String, Object> body = new HashMap<>();
        body.put("address", "12 Example Street\nTestville, TS 00000");
        body.put("phone", "555-0100");
        body.put("email", "books@example.test");
        body.put("website", "example.test");
        body.put("taxId", "EIN 00-0000000");
        body.put("paymentInstructions", "Cheques to Test sole_prop, or transfer to account 000000.");
        return body;
    }

    private byte[] pdf(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<byte[]> response = rest.exchange(base + path, HttpMethod.GET, new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).as("GET " + path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String newInvoice() {
        return api.post(base + "/invoices", Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "terms", "net_30",
                "lines", List.of(Map.of("description", "Website build", "quantity", "1",
                        "unitPrice", Map.of("amount", "1500.00", "currency", "USD"),
                        "incomeAccountId", acct.get("4010")))), HttpStatus.CREATED).get("id").asText();
    }

    private String newQuote() {
        return api.post(base + "/quotes", Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "lines", List.of(Map.of("description", "Website build", "quantity", "1",
                        "unitPrice", Map.of("amount", "1500.00", "currency", "USD"),
                        "incomeAccountId", acct.get("4010")))), HttpStatus.CREATED).get("id").asText();
    }

    @Test
    void ac1_aLetterheadIsSavedReadBackAndCleared() {
        assertThat(api.get(base + "/branding").get("hasLogo").asBoolean()).isFalse();

        JsonNode saved = api.put(base + "/branding", letterhead());
        assertThat(saved.get("phone").asText()).isEqualTo("555-0100");
        assertThat(saved.get("taxId").asText()).isEqualTo("EIN 00-0000000");
        assertThat(api.get(base + "/branding").get("email").asText()).isEqualTo("books@example.test");

        Map<String, Object> empty = new HashMap<>();
        empty.put("address", null);
        empty.put("phone", null);
        JsonNode cleared = api.put(base + "/branding", empty);
        assertThat(cleared.get("phone").isNull()).isTrue();
        assertThat(cleared.get("email").isNull()).isTrue();
    }

    @Test
    void ac2_theInvoiceCarriesTheAddressContactTaxIdAndHowToPay() throws IOException {
        api.put(base + "/branding", letterhead());
        String text = textOf(pdf("/invoices/" + newInvoice() + "/pdf"));
        assertThat(text).contains("12 Example Street")
                .contains("555-0100")
                .contains("books@example.test")
                .contains("EIN 00-0000000")
                .contains("How to pay")
                .contains("Cheques to Test sole_prop");
    }

    @Test
    void ac3_theQuoteCarriesTheAddressButNeverHowToPay() throws IOException {
        api.put(base + "/branding", letterhead());
        String text = textOf(pdf("/quotes/" + newQuote() + "/pdf"));
        assertThat(text).contains("12 Example Street").contains("555-0100");
        assertThat(text).as("a quote must never tell anyone how to pay it")
                .doesNotContain("How to pay")
                .doesNotContain("Cheques to");
    }

    @Test
    void ac4_theStatementCarriesTheAddressAndHowToPay() throws IOException {
        api.put(base + "/branding", letterhead());
        String invoiceId = newInvoice();
        api.post(base + "/invoices/" + invoiceId + "/finalize", Map.of(), HttpStatus.OK);

        String text = textOf(pdf("/customers/" + customerId + "/statement.pdf?from=" + today.minusDays(30)
                + "&to=" + today.plusDays(1)));
        assertThat(text).contains("12 Example Street").contains("How to pay").contains("Cheques to");
    }

    @Test
    void ac5_anEntityWithNoLetterheadStillPrintsEverything() throws IOException {
        String invoiceId = newInvoice();
        api.post(base + "/invoices/" + invoiceId + "/finalize", Map.of(), HttpStatus.OK);

        assertThat(textOf(pdf("/invoices/" + invoiceId + "/pdf"))).contains("Website build");
        assertThat(textOf(pdf("/quotes/" + newQuote() + "/pdf"))).contains("Website build");
        assertThat(textOf(pdf("/customers/" + customerId + "/statement.pdf?from=" + today.minusDays(30)
                + "&to=" + today.plusDays(1)))).contains("STATEMENT");
    }

    @Test
    void ac6_onlyARealPngOrJpegOfASaneSizeIsAccepted() throws IOException {
        assertThat(api.putFile(base + "/branding/logo", "logo.png", PNG, HttpStatus.OK)
                .get("hasLogo").asBoolean()).isTrue();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        ResponseEntity<byte[]> stored = rest.exchange(base + "/branding/logo", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored.getBody()).isEqualTo(PNG);

        try (PDDocument document = Loader.loadPDF(pdf("/invoices/" + newInvoice() + "/pdf"))) {
            PDPage page = document.getPage(0);
            assertThat(page.getResources().getXObjectNames().iterator().hasNext())
                    .as("the logo must actually be drawn on the page").isTrue();
        }

        byte[] notAnImage = "<?php echo 'hello'; ?>".getBytes(StandardCharsets.UTF_8);
        assertThat(api.putFile(base + "/branding/logo", "logo.png", notAnImage, HttpStatus.BAD_REQUEST)
                .toString()).contains("UNSUPPORTED_LOGO");

        byte[] huge = new byte[2 * 1024 * 1024];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertThat(api.putFile(base + "/branding/logo", "big.png", huge, HttpStatus.BAD_REQUEST)
                .toString()).contains("LOGO_TOO_LARGE");

        api.delete(base + "/branding/logo", HttpStatus.NO_CONTENT);
        assertThat(api.get(base + "/branding").get("hasLogo").asBoolean()).isFalse();
        assertThat(rest.exchange(base + "/branding/logo", HttpMethod.GET, new HttpEntity<>(headers), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac7_onlyOwnersAndAdminsMayChangeItAndAnotherOrgSeesNothing() {
        ApiClient bookkeeper = new ApiClient(rest);
        api.post("/api/v1/orgs/" + orgId + "/members",
                Map.of("email", bookkeeper.email(), "role", "bookkeeper"), HttpStatus.CREATED);

        assertThat(bookkeeper.get(base + "/branding").get("hasLogo").asBoolean()).isFalse();
        bookkeeper.put2(base + "/branding", letterhead(), HttpStatus.FORBIDDEN);

        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/branding", HttpStatus.NOT_FOUND);
        outsider.put2(base + "/branding", letterhead(), HttpStatus.NOT_FOUND);
    }
}
