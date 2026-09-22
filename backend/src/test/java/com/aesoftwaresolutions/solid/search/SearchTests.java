package com.aesoftwaresolutions.solid.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 042. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SearchTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private void journal(String date, String memo, String debit, String credit, String amount, boolean post) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("memo", memo);
        body.put("post", post);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debit), "amount", money(amount)),
                Map.of("accountId", acct.get(credit), "amount", money("-" + amount))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private JsonNode search(String q) {
        // TestRestTemplate expands the URL itself, so the query goes in plain and unencoded.
        return api.get(base + "/search?q=" + q.replace(" ", "+"));
    }

    private static Optional<JsonNode> group(JsonNode results, String kind) {
        return java.util.stream.StreamSupport.stream(results.get("groups").spliterator(), false)
                .filter(g -> g.get("kind").asText().equals(kind)).findFirst();
    }

    private static JsonNode requireGroup(JsonNode results, String kind) {
        return group(results, kind).orElseThrow(() -> new AssertionError("no " + kind + " group in " + results));
    }

    @Test
    void ac1_ac2_oneWordFindsWhateverItIsWritten0n() {
        journal("2026-03-04", "Springfield Printing deposit slip", "1010", "4010", "1200.00", true);
        String customerId = api.post(base + "/customers", Map.of("name", "Springfield Printing"), HttpStatus.CREATED)
                .get("id").asText();
        api.post(base + "/invoices", Map.of("customerId", customerId, "issueDate", "2026-03-10",
                        "terms", "net_30", "invoiceNumber", "SPRINGFIELD-1",
                        "lines", List.of(Map.of("incomeAccountId", acct.get("4010"), "description", "Printing",
                                "quantity", "1", "unitPrice", money("420.00")))),
                HttpStatus.CREATED);
        api.post(base + "/vendors", Map.of("name", "Springfield Supplies"), HttpStatus.CREATED);
        api.postFile(base + "/documents?kind=receipt", "springfield-receipt.pdf",
                "%PDF-1.4 fake".getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.CREATED);

        JsonNode results = search("springfield");

        assertThat(requireGroup(results, "journal_entry").get("hits").get(0).get("label").asText())
                .contains("Springfield Printing");
        assertThat(requireGroup(results, "journal_entry").get("hits").get(0).get("amount").get("amount").asText())
                .isEqualTo("1200.00");
        assertThat(requireGroup(results, "customer").get("total").asInt()).isEqualTo(1);
        assertThat(requireGroup(results, "invoice").get("hits").get(0).get("label").asText())
                .isEqualTo("Invoice SPRINGFIELD-1");
        assertThat(requireGroup(results, "vendor").get("total").asInt()).isEqualTo(1);
        assertThat(requireGroup(results, "document").get("hits").get(0).get("label").asText())
                .isEqualTo("springfield-receipt.pdf");
        // Each hit names the screen that shows it, so the result can be a link.
        assertThat(requireGroup(results, "invoice").get("hits").get(0).get("where").asText()).isEqualTo("sales");
    }

    @Test
    void ac3_anAmountIsRecognisedAndMatchedExactly() {
        journal("2026-03-04", "Printer repair", "6220", "1010", "420.00", true);
        journal("2026-03-05", "Something else", "6220", "1010", "42.00", true);

        JsonNode results = search("420");

        assertThat(results.get("amountInterpreted").get("amount").asText()).isEqualTo("420.00");
        JsonNode entries = requireGroup(results, "journal_entry");
        assertThat(entries.get("total").asInt()).isEqualTo(1);
        assertThat(entries.get("hits").get(0).get("label").asText()).isEqualTo("Printer repair");
    }

    @Test
    void ac4_ac6_nothingFoundIsAnEmptyAnswerAndOneLetterIsNotASearch() {
        journal("2026-03-04", "Printer repair", "6220", "1010", "420.00", true);

        assertThat(search("zzzznothing").get("groups")).isEmpty();
        assertThat(search("p").get("groups")).isEmpty();
        assertThat(search("p").get("amountInterpreted").isNull()).isTrue();
    }

    @Test
    void ac5_aCommonWordIsCappedAndTheRealCountIsReported() {
        for (int i = 1; i <= 12; i++) {
            journal("2026-04-" + String.format("%02d", i), "Coffee run " + i, "6220", "1010", i + ".00", true);
        }

        JsonNode entries = requireGroup(search("coffee"), "journal_entry");

        assertThat(entries.get("total").asInt()).isEqualTo(12);
        assertThat(entries.get("hits")).hasSize(10);
        // Newest first, so the most recent is the one at the top.
        assertThat(entries.get("hits").get(0).get("label").asText()).isEqualTo("Coffee run 12");
    }

    /** Spec 043: a wildcard typed by the person is a character to look for, not a licence to scan everything. */
    @Test
    void percentIsTreatedAsTextRatherThanAsAWildcard() {
        journal("2026-03-04", "50% deposit on the press", "6220", "1010", "420.00", true);
        journal("2026-03-05", "Ordinary coffee", "6220", "1010", "5.00", true);

        assertThat(group(search("50%"), "journal_entry").orElseThrow().get("total").asInt()).isEqualTo(1);
        // '%%' would once have matched every row of every table; now it matches the text '%%', which is nowhere.
        assertThat(search("%%").get("groups")).isEmpty();
    }

    /**
     * Spec 043: an amount finds a bill of that size. The predicate uses {@code abs()} so it keeps the promise
     * the page makes — "whichever way the money went" — even though today a bill total cannot be negative.
     */
    @Test
    void anAmountFindsABillOfThatSize() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Press Co"), HttpStatus.CREATED)
                .get("id").asText();
        api.post(base + "/bills", Map.of("vendorId", vendorId, "billDate", "2026-03-04", "terms", "net_30",
                        "lines", List.of(Map.of("expenseAccountId", acct.get("6220"), "description", "Printing",
                                "amount", money("420.00")))),
                HttpStatus.CREATED);

        assertThat(requireGroup(search("420"), "bill").get("total").asInt()).isEqualTo(1);
    }

    @Test
    void ac7_ac8_anotherEntityAndAnotherOrganizationStayOut() {
        journal("2026-03-04", "Printer repair", "6220", "1010", "420.00", true);
        String other = api.newEntity(org, "sole_prop");

        assertThat(api.get("/api/v1/orgs/" + org + "/entities/" + other + "/search?q=printer").get("groups"))
                .isEmpty();
        new ApiClient(rest).get(base + "/search?q=printer", HttpStatus.NOT_FOUND);
    }
}
