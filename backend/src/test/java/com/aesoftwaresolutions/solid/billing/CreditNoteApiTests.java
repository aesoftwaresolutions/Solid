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

/** Spec 057. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CreditNoteApiTests {

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
        customerId = api.post(base + "/customers", Map.of("name", "Northwind Traders"), HttpStatus.CREATED)
                .get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    /** An issued invoice for the given amount. */
    private JsonNode issuedInvoice(String amount) {
        String id = api.post(base + "/invoices", Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "terms", "net_30",
                "lines", List.of(Map.of("description", "Website build", "quantity", "1",
                        "unitPrice", money(amount), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CREATED).get("id").asText();
        return api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
    }

    private JsonNode creditNote(String amount) {
        return creditNote(amount, customerId);
    }

    private JsonNode creditNote(String amount, String forCustomer) {
        return api.post(base + "/credit-notes", Map.of(
                "customerId", forCustomer,
                "issueDate", today.toString(),
                "memo", "Overcharged for the last milestone",
                "lines", List.of(Map.of("description", "Correction", "quantity", "1",
                        "unitPrice", money(amount), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CREATED);
    }

    private String receivableBalance() {
        JsonNode balanceSheet = api.get(base + "/reports/balance-sheet?asOf=" + today.plusDays(1));
        for (JsonNode section : List.of(balanceSheet.get("assets"))) {
            for (JsonNode row : section.get("rows")) {
                if (row.get("code").asText().equals("1100")) {
                    return row.get("amount").get("amount").asText();
                }
            }
        }
        throw new AssertionError("No receivable row in " + balanceSheet.get("assets"));
    }

    private JsonNode invoice(String invoiceId) {
        return api.get(base + "/invoices/" + invoiceId);
    }

    @Test
    void ac1_issuingPostsTheMirrorOfAnInvoiceAndDropsWhatIsOwed() {
        String invoiceId = issuedInvoice("1000.00").get("id").asText();
        assertThat(receivableBalance()).isEqualTo("1000.00");

        JsonNode credit = creditNote("250.00");
        assertThat(credit.get("creditNumber").asText()).isEqualTo("CN-0001");
        assertThat(credit.get("status").asText()).isEqualTo("draft");
        assertThat(credit.get("total").get("amount").asText()).isEqualTo("250.00");
        // A draft credit note is not in the books.
        assertThat(receivableBalance()).isEqualTo("1000.00");

        JsonNode issued = api.post(base + "/credit-notes/" + credit.get("id").asText() + "/issue", Map.of(),
                HttpStatus.OK);
        assertThat(issued.get("status").asText()).isEqualTo("issued");
        assertThat(issued.get("journalEntryId").isNull()).isFalse();
        assertThat(issued.get("remaining").get("amount").asText()).isEqualTo("250.00");
        assertThat(receivableBalance()).as("issuing moves the ledger").isEqualTo("750.00");

        // The income it reverses comes back out of the profit and loss.
        assertThat(api.get(base + "/reports/profit-and-loss?from=" + today.minusDays(1) + "&to="
                + today.plusDays(1)).get("netIncome").get("amount").asText()).isEqualTo("750.00");
        assertThat(invoice(invoiceId).get("balanceDue").get("amount").asText())
                .as("until it is applied, the invoice itself is untouched").isEqualTo("1000.00");
    }

    @Test
    void ac2_ac3_applyingChangesTheInvoiceButPostsNothing() {
        String invoiceId = issuedInvoice("1000.00").get("id").asText();
        String creditId = creditNote("250.00").get("id").asText();
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);
        String before = receivableBalance();
        int entriesBefore = api.get(base + "/journal-entries").size();

        JsonNode applied = api.post(base + "/credit-notes/" + creditId + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("250.00")), HttpStatus.OK);
        assertThat(applied.get("applied").get("amount").asText()).isEqualTo("250.00");
        assertThat(applied.get("remaining").get("amount").asText()).isEqualTo("0.00");
        assertThat(applied.get("applications").get(0).get("invoiceNumber").asText()).isNotBlank();

        assertThat(receivableBalance()).as("applying posts nothing").isEqualTo(before);
        assertThat(api.get(base + "/journal-entries").size()).isEqualTo(entriesBefore);

        JsonNode after = invoice(invoiceId);
        assertThat(after.get("creditsApplied").get("amount").asText()).isEqualTo("250.00");
        assertThat(after.get("balanceDue").get("amount").asText()).isEqualTo("750.00");
        assertThat(after.get("status").asText()).isEqualTo("open");

        // AC3: crediting the rest settles it, even though no money arrived.
        String rest2 = creditNote("750.00").get("id").asText();
        api.post(base + "/credit-notes/" + rest2 + "/issue", Map.of(), HttpStatus.OK);
        api.post(base + "/credit-notes/" + rest2 + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("750.00")), HttpStatus.OK);
        assertThat(invoice(invoiceId).get("status").asText()).isEqualTo("paid");
        assertThat(invoice(invoiceId).get("balanceDue").get("amount").asText()).isEqualTo("0.00");
    }

    @Test
    void ac4_overApplyingAndTheWrongCustomerAreRefused() {
        String invoiceId = issuedInvoice("100.00").get("id").asText();
        String creditId = creditNote("500.00").get("id").asText();

        assertThat(api.post(base + "/credit-notes/" + creditId + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("50.00")), HttpStatus.CONFLICT).toString())
                .contains("CREDIT_NOTE_WRONG_STATUS");
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);

        // More than the invoice has outstanding.
        assertThat(api.post(base + "/credit-notes/" + creditId + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("150.00")), HttpStatus.CONFLICT).toString())
                .contains("OVER_APPLIED");

        // More than the credit has left.
        String small = creditNote("40.00").get("id").asText();
        api.post(base + "/credit-notes/" + small + "/issue", Map.of(), HttpStatus.OK);
        api.post(base + "/credit-notes/" + small + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("40.00")), HttpStatus.OK);
        assertThat(api.post(base + "/credit-notes/" + small + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("1.00")), HttpStatus.CONFLICT).toString())
                .contains("OVER_APPLIED");

        // Another customer's invoice.
        String other = api.post(base + "/customers", Map.of("name", "Someone Else"), HttpStatus.CREATED)
                .get("id").asText();
        String otherCredit = creditNote("100.00", other).get("id").asText();
        api.post(base + "/credit-notes/" + otherCredit + "/issue", Map.of(), HttpStatus.OK);
        assertThat(api.post(base + "/credit-notes/" + otherCredit + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("10.00")), HttpStatus.CONFLICT).toString())
                .contains("WRONG_CUSTOMER");
    }

    @Test
    void ac5_ac6_unapplyingGivesItBackAndVoidingUndoesTheEntry() {
        String invoiceId = issuedInvoice("1000.00").get("id").asText();
        String creditId = creditNote("400.00").get("id").asText();
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);
        JsonNode applied = api.post(base + "/credit-notes/" + creditId + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("400.00")), HttpStatus.OK);
        String applicationId = applied.get("applications").get(0).get("id").asText();

        assertThat(api.post(base + "/credit-notes/" + creditId + "/void", Map.of(), HttpStatus.CONFLICT)
                .toString()).contains("CREDIT_NOTE_APPLIED");

        api.delete(base + "/credit-notes/" + creditId + "/applications/" + applicationId, HttpStatus.NO_CONTENT);
        assertThat(api.get(base + "/credit-notes/" + creditId).get("remaining").get("amount").asText())
                .isEqualTo("400.00");
        assertThat(invoice(invoiceId).get("balanceDue").get("amount").asText()).isEqualTo("1000.00");

        assertThat(api.post(base + "/credit-notes/" + creditId + "/void", Map.of(), HttpStatus.OK)
                .get("status").asText()).isEqualTo("void");
        assertThat(receivableBalance()).as("voiding puts the receivable back").isEqualTo("1000.00");
    }

    @Test
    void ac7_agingAndTheStatementBothCountTheCredit() {
        String invoiceId = issuedInvoice("1000.00").get("id").asText();
        String creditId = creditNote("300.00").get("id").asText();
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);
        api.post(base + "/credit-notes/" + creditId + "/applications",
                Map.of("invoiceId", invoiceId, "amount", money("300.00")), HttpStatus.OK);

        assertThat(api.get(base + "/reports/accounts-receivable-aging?asOf=" + today.plusDays(1))
                .get("totals").get("total").get("amount").asText()).isEqualTo("700.00");
        assertThat(receivableBalance()).as("the report and the ledger agree").isEqualTo("700.00");

        JsonNode statement = api.get(base + "/customers/" + customerId + "/statement?from="
                + today.minusDays(5) + "&to=" + today.plusDays(1));
        assertThat(statement.get("closingBalance").get("amount").asText()).isEqualTo("700.00");
        assertThat(statement.toString()).contains("CN-0001").contains("credit_note");
    }

    @Test
    void aDraftCanBeEditedAndOnlyADraftCanBe() {
        JsonNode credit = creditNote("100.00");
        String id = credit.get("id").asText();
        JsonNode edited = api.patch(base + "/credit-notes/" + id, Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "memo", "Changed my mind about the amount",
                "lines", List.of(Map.of("description", "Correction", "quantity", "2",
                        "unitPrice", money("100.00"), "incomeAccountId", acct.get("4010")))), HttpStatus.OK);
        assertThat(edited.get("total").get("amount").asText()).isEqualTo("200.00");

        api.post(base + "/credit-notes/" + id + "/issue", Map.of(), HttpStatus.OK);
        assertThat(api.patch(base + "/credit-notes/" + id, Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "lines", List.of(Map.of("description", "Correction", "quantity", "1",
                        "unitPrice", money("100.00"), "incomeAccountId", acct.get("4010")))),
                HttpStatus.CONFLICT).toString()).contains("CREDIT_NOTE_WRONG_STATUS");
    }

    @Test
    void ac8_anotherOrganizationSeesNothing() {
        String creditId = creditNote("100.00").get("id").asText();
        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/credit-notes", HttpStatus.NOT_FOUND);
        outsider.get(base + "/credit-notes/" + creditId, HttpStatus.NOT_FOUND);
        outsider.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.NOT_FOUND);
    }
}
