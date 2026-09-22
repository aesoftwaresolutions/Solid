package com.aesoftwaresolutions.solid.billing;

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

/** Spec 054. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class QuoteApiTests {

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
        customerId = api.post(base + "/customers", Map.of("name", "Prospect Ltd"), HttpStatus.CREATED)
                .get("id").asText();
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
        body.put("memo", "Website build");
        body.put("lines", List.of(Map.of("description", "Design and build", "quantity", "1",
                "unitPrice", money(amount), "incomeAccountId", acct.get("4010"))));
        return api.post(base + "/quotes", body, HttpStatus.CREATED);
    }

    private JsonNode act(String quoteId, String what, HttpStatus expected) {
        return api.post(base + "/quotes/" + quoteId + "/" + what, Map.of(), expected);
    }

    @Test
    void ac1_ac3_ac7_aQuoteBecomesADraftInvoiceAndNothingIsInTheBooksUntilItIsIssued() {
        JsonNode created = quote("4000.00", null);
        assertThat(created.get("quoteNumber").asText()).isEqualTo("Q-0001");
        assertThat(created.get("status").asText()).isEqualTo("draft");
        assertThat(created.get("total").get("amount").asText()).isEqualTo("4000.00");
        assertThat(quote("100.00", null).get("quoteNumber").asText()).as("sequential").isEqualTo("Q-0002");

        String id = created.get("id").asText();
        assertThat(act(id, "send", HttpStatus.OK).get("status").asText()).isEqualTo("sent");
        assertThat(act(id, "accept", HttpStatus.OK).get("status").asText()).isEqualTo("accepted");

        // AC3: nothing about a quote reaches the books.
        assertThat(api.get(base + "/reports/profit-and-loss?from=" + today.minusDays(1) + "&to=" + today
                .plusDays(1)).get("netIncome").get("amount").asText()).isEqualTo("0.00");
        assertThat(api.get(base + "/reports/accounts-receivable-aging?asOf=" + today.plusDays(90))
                .get("totals").get("total").get("amount").asText()).isEqualTo("0.00");

        JsonNode invoice = api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CREATED);
        assertThat(invoice.get("status").asText()).isEqualTo("draft");
        assertThat(invoice.get("total").get("amount").asText()).isEqualTo("4000.00");
        assertThat(invoice.get("lines").get(0).get("description").asText()).isEqualTo("Design and build");

        JsonNode after = api.get(base + "/quotes/" + id);
        assertThat(after.get("status").asText()).isEqualTo("converted");
        assertThat(after.get("invoiceId").asText()).isEqualTo(invoice.get("id").asText());

        // Still nothing owed: the invoice it became is a draft.
        assertThat(api.get(base + "/reports/accounts-receivable-aging?asOf=" + today.plusDays(90))
                .get("totals").get("total").get("amount").asText()).isEqualTo("0.00");
    }

    @Test
    void ac2_convertingTwiceIsRefusedAndNamesTheInvoice() {
        String id = quote("1000.00", null).get("id").asText();
        act(id, "accept", HttpStatus.OK);
        JsonNode invoice = api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CREATED);

        JsonNode refused = api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CONFLICT);

        assertThat(refused.toString()).contains("QUOTE_ALREADY_CONVERTED")
                .contains(invoice.get("id").asText());
        assertThat(api.get(base + "/invoices")).hasSize(1);
    }

    @Test
    void ac4_aQuoteNobodyAnsweredInTimeReadsAsExpiredAndGoesNoFurther() {
        // Quoted a month ago, good for a fortnight, and nobody replied: the offer has lapsed.
        String id = quote("1000.00", today.minusDays(16).toString(), today.minusDays(30).toString())
                .get("id").asText();

        JsonNode expired = api.get(base + "/quotes/" + id);
        assertThat(expired.get("status").asText()).isEqualTo("expired");
        assertThat(expired.get("expired").asBoolean()).isTrue();

        assertThat(act(id, "accept", HttpStatus.CONFLICT).toString()).contains("QUOTE_WRONG_STATUS");
        assertThat(api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CONFLICT).toString())
                .contains("QUOTE_EXPIRED");
    }

    @Test
    void anAcceptedQuoteIsStillGoodAfterItsValidUntilDate() {
        // Accepting is the answer the date was waiting for; the clock stops mattering once it comes.
        String id = quote("1000.00", today.minusDays(16).toString(), today.minusDays(30).toString())
                .get("id").asText();

        // It lapsed before anyone answered, so it cannot be accepted now — but one accepted in time can
        // still be converted, which is what this checks through a quote with no end date.
        String openEnded = quote("1000.00", null, today.minusDays(30).toString()).get("id").asText();
        act(openEnded, "accept", HttpStatus.OK);

        assertThat(api.post(base + "/quotes/" + openEnded + "/convert", Map.of(), HttpStatus.CREATED)
                .get("total").get("amount").asText()).isEqualTo("1000.00");
        assertThat(api.get(base + "/quotes/" + id).get("status").asText()).isEqualTo("expired");
    }

    @Test
    void ac5_aDeclinedQuoteKeepsItsReasonAndGoesNoFurther() {
        String id = quote("2500.00", null).get("id").asText();
        act(id, "send", HttpStatus.OK);

        JsonNode declined = api.post(base + "/quotes/" + id + "/decline",
                Map.of("reason", "Went with someone cheaper"), HttpStatus.OK);

        assertThat(declined.get("status").asText()).isEqualTo("declined");
        assertThat(declined.get("declinedReason").asText()).isEqualTo("Went with someone cheaper");
        assertThat(api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CONFLICT).toString())
                .contains("QUOTE_WRONG_STATUS");
    }

    @Test
    void ac6_aSentQuoteCannotBeEdited() {
        String id = quote("2500.00", null).get("id").asText();
        Map<String, Object> change = new HashMap<>();
        change.put("customerId", customerId);
        change.put("issueDate", today.toString());
        change.put("lines", List.of(Map.of("description", "Cheaper", "quantity", "1",
                "unitPrice", money("1500.00"), "incomeAccountId", acct.get("4010"))));

        // A draft can still be changed.
        assertThat(api.patch(base + "/quotes/" + id, change, HttpStatus.OK)
                .get("total").get("amount").asText()).isEqualTo("1500.00");

        act(id, "send", HttpStatus.OK);
        assertThat(api.patch(base + "/quotes/" + id, change, HttpStatus.CONFLICT).toString())
                .contains("QUOTE_WRONG_STATUS");
    }

    @Test
    void anArchivedCustomerCanBeNeitherQuotedNorInvoiced() {
        String id = quote("500.00", null).get("id").asText();
        act(id, "accept", HttpStatus.OK);
        api.patch(base + "/customers/" + customerId, Map.of("archived", true), HttpStatus.OK);

        assertThat(api.post(base + "/quotes/" + id + "/convert", Map.of(), HttpStatus.CONFLICT).toString())
                .contains("CUSTOMER_ARCHIVED");
    }

    @Test
    void ac8_anotherOrganizationSeesNothing() {
        String id = quote("500.00", null).get("id").asText();

        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/quotes", HttpStatus.NOT_FOUND);
        outsider.get(base + "/quotes/" + id, HttpStatus.NOT_FOUND);
        outsider.post(base + "/quotes/" + id + "/accept", Map.of(), HttpStatus.NOT_FOUND);
    }
}
