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

/**
 * Spec 058 — the CPA's ruling on sales tax in a credit note, recorded in
 * docs/tax-sources/credit-note-sales-tax.md.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CreditNoteTaxTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();
    String customerId;
    String rateId;
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
        // A made-up jurisdiction and rate: this test is about the arithmetic, not about any real state's rate.
        rateId = api.post(base + "/sales-tax-rates", Map.of(
                "jurisdiction", "Testville", "ratePercent", "8.25",
                "liabilityAccountId", acct.get("2200"),
                "effectiveFrom", today.minusYears(2).toString(),
                "note", "Fixture rate for spec 058 — not a real rate"), HttpStatus.CREATED).get("id").asText();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    /** An issued invoice with one taxed line of the given amount. */
    private JsonNode taxedInvoice(String amount) {
        String id = api.post(base + "/invoices", Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "terms", "net_30",
                "lines", List.of(Map.of("description", "Widgets", "quantity", "1",
                        "unitPrice", money(amount), "incomeAccountId", acct.get("4010"),
                        "taxRateId", rateId))), HttpStatus.CREATED).get("id").asText();
        return api.post(base + "/invoices/" + id + "/finalize", Map.of(), HttpStatus.OK);
    }

    private JsonNode credit(String amount, String invoiceLineId, HttpStatus expected) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", "Returned widgets");
        line.put("quantity", "1");
        line.put("unitPrice", money(amount));
        line.put("incomeAccountId", acct.get("4010"));
        line.put("invoiceLineId", invoiceLineId);
        return api.post(base + "/credit-notes", Map.of(
                "customerId", customerId,
                "issueDate", today.toString(),
                "lines", List.of(line)), expected);
    }

    private String liabilityBalance() {
        JsonNode sheet = api.get(base + "/reports/balance-sheet?asOf=" + today.plusDays(1));
        for (JsonNode row : sheet.get("liabilities").get("rows")) {
            if (row.get("code").asText().equals("2200")) {
                return row.get("amount").get("amount").asText();
            }
        }
        return "0.00";
    }

    @Test
    void ac1_ac4_aFullCreditGivesBackExactlyTheTaxThatWasCharged() {
        // 100.07 at 8.25% rounds to 8.26; a recomputation on the way back out could easily land on 8.25.
        JsonNode invoice = taxedInvoice("100.07");
        JsonNode invoiceLine = invoice.get("lines").get(0);
        assertThat(invoiceLine.get("taxAmount").get("amount").asText()).isEqualTo("8.26");
        assertThat(liabilityBalance()).isEqualTo("8.26");

        JsonNode credit = credit("100.07", invoiceLine.get("id").asText(), HttpStatus.CREATED);
        assertThat(credit.get("lines").get(0).get("taxAmount").get("amount").asText())
                .as("the exact tax originally charged, not a recomputation").isEqualTo("8.26");
        assertThat(credit.get("taxTotal").get("amount").asText()).isEqualTo("8.26");
        assertThat(credit.get("total").get("amount").asText()).isEqualTo("108.33");

        api.post(base + "/credit-notes/" + credit.get("id").asText() + "/issue", Map.of(), HttpStatus.OK);
        assertThat(liabilityBalance()).as("net tax owed for the transaction is zero").isEqualTo("0.00");
    }

    @Test
    void ac2_aPartialCreditReversesOnlyTheTaxOnWhatWasCredited() {
        JsonNode invoice = taxedInvoice("1000.00");
        String lineId = invoice.get("lines").get(0).get("id").asText();
        assertThat(liabilityBalance()).isEqualTo("82.50");

        JsonNode credit = credit("400.00", lineId, HttpStatus.CREATED);
        // 400.00 × 8.25% = 33.00
        assertThat(credit.get("lines").get(0).get("taxAmount").get("amount").asText()).isEqualTo("33.00");
        assertThat(credit.get("total").get("amount").asText()).isEqualTo("433.00");

        api.post(base + "/credit-notes/" + credit.get("id").asText() + "/issue", Map.of(), HttpStatus.OK);
        assertThat(liabilityBalance()).as("only the credited portion comes back out").isEqualTo("49.50");
    }

    @Test
    void ac3_theOriginalRateIsUsedEvenAfterItHasBeenRetired() {
        JsonNode invoice = taxedInvoice("1000.00");
        String lineId = invoice.get("lines").get(0).get("id").asText();

        // The jurisdiction changes its rate: the old one is retired and a new one takes over.
        api.post(base + "/sales-tax-rates/" + rateId + "/deactivate", Map.of(), HttpStatus.OK);
        api.post(base + "/sales-tax-rates", Map.of(
                "jurisdiction", "Testville", "ratePercent", "10.00",
                "liabilityAccountId", acct.get("2200"),
                "effectiveFrom", today.toString(),
                "note", "Fixture rate for spec 058 — not a real rate"), HttpStatus.CREATED);

        JsonNode credit = credit("400.00", lineId, HttpStatus.CREATED);
        assertThat(credit.get("lines").get(0).get("taxAmount").get("amount").asText())
                .as("the rate the invoice used, not today's").isEqualTo("33.00");
    }

    @Test
    void ac5_theSalesTaxReportNetsAFullyCreditedInvoiceToZero() {
        JsonNode invoice = taxedInvoice("1000.00");
        String lineId = invoice.get("lines").get(0).get("id").asText();
        JsonNode report = api.get(base + "/reports/sales-tax?from=" + today.minusDays(1) + "&to="
                + today.plusDays(1));
        assertThat(report.get("totalCollected").get("amount").asText()).isEqualTo("82.50");
        assertThat(report.get("totalTaxable").get("amount").asText()).isEqualTo("1000.00");

        String creditId = credit("1000.00", lineId, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);

        JsonNode after = api.get(base + "/reports/sales-tax?from=" + today.minusDays(1) + "&to="
                + today.plusDays(1));
        assertThat(after.get("totalCollected").get("amount").asText()).isEqualTo("0.00");
        assertThat(after.get("totalTaxable").get("amount").asText()).isEqualTo("0.00");
    }

    /** Spec 059 AC 1-4: the period a credit falls in, and what the report says about it. */
    @Test
    void spec059_theReportSeparatesChargedFromCreditedAndNamesPriorPeriodAdjustments() {
        // An invoice from last month, credited this month: the classic case the CPA's caveat is about.
        LocalDate lastMonth = today.minusMonths(1);
        String oldInvoice = api.post(base + "/invoices", Map.of(
                "customerId", customerId,
                "issueDate", lastMonth.toString(),
                "terms", "net_30",
                "lines", List.of(Map.of("description", "Widgets", "quantity", "1",
                        "unitPrice", money("1000.00"), "incomeAccountId", acct.get("4010"),
                        "taxRateId", rateId))), HttpStatus.CREATED).get("id").asText();
        String oldLineId = api.post(base + "/invoices/" + oldInvoice + "/finalize", Map.of(), HttpStatus.OK)
                .get("lines").get(0).get("id").asText();
        // And an invoice inside this period, also credited, which is not an adjustment to anything filed.
        String thisPeriodLine = taxedInvoice("500.00").get("lines").get(0).get("id").asText();

        String creditOld = credit("1000.00", oldLineId, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + creditOld + "/issue", Map.of(), HttpStatus.OK);
        String creditNew = credit("200.00", thisPeriodLine, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + creditNew + "/issue", Map.of(), HttpStatus.OK);

        JsonNode report = api.get(base + "/reports/sales-tax?from=" + today.withDayOfMonth(1) + "&to="
                + today.plusDays(1));
        JsonNode row = report.get("jurisdictions").get(0);
        // AC1: charged and credited are both visible, and the net is charged minus credited.
        assertThat(row.get("taxCharged").get("amount").asText()).isEqualTo("41.25");       // 500.00 × 8.25%
        assertThat(row.get("taxCredited").get("amount").asText()).isEqualTo("99.00");      // 82.50 + 16.50
        assertThat(row.get("taxCollected").get("amount").asText()).isEqualTo("-57.75");
        assertThat(row.get("taxableCharged").get("amount").asText()).isEqualTo("500.00");
        assertThat(row.get("taxableCredited").get("amount").asText()).isEqualTo("1200.00");

        // AC2: last month's invoice, credited this month, is named — and its tax still counts here.
        JsonNode adjustments = report.get("priorPeriodAdjustments");
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).get("invoiceDate").asText()).isEqualTo(lastMonth.toString());
        assertThat(adjustments.get(0).get("creditDate").asText()).isEqualTo(today.toString());
        assertThat(adjustments.get(0).get("taxReversed").get("amount").asText()).isEqualTo("82.50");
        assertThat(adjustments.get(0).get("invoiceNumber").asText()).isNotBlank();
        assertThat(adjustments.get(0).get("creditNumber").asText()).isNotBlank();
        assertThat(report.get("totalCollected").get("amount").asText())
                .as("the old period is not reopened; the reversal lands here").isEqualTo("-57.75");

        // AC4: the report says which model this is.
        assertThat(report.get("note").asText())
                .contains("ongoing adjustment").contains("amended");
    }

    @Test
    void spec059_aCreditWithinItsOwnPeriodIsNotAnAdjustmentToAnything() {
        String lineId = taxedInvoice("500.00").get("lines").get(0).get("id").asText();
        String creditId = credit("500.00", lineId, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + creditId + "/issue", Map.of(), HttpStatus.OK);

        JsonNode report = api.get(base + "/reports/sales-tax?from=" + today.minusDays(1) + "&to="
                + today.plusDays(1));
        assertThat(report.get("priorPeriodAdjustments")).isEmpty();
        assertThat(report.get("totalCollected").get("amount").asText()).isEqualTo("0.00");
    }

    @Test
    void ac6_aCreditThatNamesNoLineCarriesNoTax() {
        JsonNode credit = credit("100.00", null, HttpStatus.CREATED);
        assertThat(credit.get("taxTotal").get("amount").asText()).isEqualTo("0.00");
        assertThat(credit.get("total").get("amount").asText()).isEqualTo("100.00");
        assertThat(credit.get("lines").get(0).get("taxRateId").isNull()).isTrue();
    }

    @Test
    void ac7_aLineCannotBeCreditedForMoreThanItWasCharged() {
        JsonNode invoice = taxedInvoice("100.00");
        String lineId = invoice.get("lines").get(0).get("id").asText();

        assertThat(credit("150.00", lineId, HttpStatus.CONFLICT).toString()).contains("OVER_CREDITED");

        String firstId = credit("60.00", lineId, HttpStatus.CREATED).get("id").asText();
        api.post(base + "/credit-notes/" + firstId + "/issue", Map.of(), HttpStatus.OK);
        assertThat(credit("50.00", lineId, HttpStatus.CONFLICT).toString())
                .as("what is already credited counts").contains("OVER_CREDITED");
        // What is left still goes through, and takes the rest of the tax with it.
        JsonNode rest = credit("40.00", lineId, HttpStatus.CREATED);
        assertThat(rest.get("lines").get(0).get("taxAmount").get("amount").asText()).isEqualTo("3.30");
    }

    @Test
    void creditingAnotherCustomersLineIsRefused() {
        JsonNode invoice = taxedInvoice("100.00");
        String lineId = invoice.get("lines").get(0).get("id").asText();
        String other = api.post(base + "/customers", Map.of("name", "Someone Else"), HttpStatus.CREATED)
                .get("id").asText();

        Map<String, Object> line = new HashMap<>();
        line.put("description", "Returned widgets");
        line.put("quantity", "1");
        line.put("unitPrice", money("10.00"));
        line.put("incomeAccountId", acct.get("4010"));
        line.put("invoiceLineId", lineId);
        assertThat(api.post(base + "/credit-notes", Map.of(
                "customerId", other,
                "issueDate", today.toString(),
                "lines", List.of(line)), HttpStatus.CONFLICT).toString()).contains("WRONG_CUSTOMER");
    }
}
