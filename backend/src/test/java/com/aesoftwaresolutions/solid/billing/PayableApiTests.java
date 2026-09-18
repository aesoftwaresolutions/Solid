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

/** Spec 013. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PayableApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private String vendor(String name, boolean is1099, String taxIdLast4, String classification) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("is1099Vendor", is1099);
        if (taxIdLast4 != null) {
            body.put("taxIdLast4", taxIdLast4);
        }
        if (classification != null) {
            body.put("taxClassification", classification);
        }
        return api.post(base + "/vendors", body, HttpStatus.CREATED).get("id").asText();
    }

    private String bill(String vendorId, String billDate, String terms, String amount, String accountCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("vendorId", vendorId);
        body.put("billDate", billDate);
        body.put("terms", terms);
        body.put("lines", List.of(Map.of("description", "Work", "amount", money(amount),
                "expenseAccountId", acct.get(accountCode))));
        return api.post(base + "/bills", body, HttpStatus.CREATED).get("id").asText();
    }

    private String approvedBill(String vendorId, String billDate, String terms, String amount, String accountCode) {
        String id = bill(vendorId, billDate, terms, amount, accountCode);
        api.post(base + "/bills/" + id + "/approve", Map.of(), HttpStatus.OK);
        return id;
    }

    private void pay(String vendorId, String paidDate, String billId, String amount) {
        api.post(base + "/bill-payments", Map.of("vendorId", vendorId, "paidDate", paidDate,
                "paymentAccountId", acct.get("1010"),
                "applications", List.of(Map.of("billId", billId, "amount", money(amount)))), HttpStatus.CREATED);
    }

    private static String amt(JsonNode m) {
        return m.get("amount").asText();
    }

    @Test
    void ac1_ac2_approvePostsExpenseAndPayable() {
        String v = vendor("Freelance Designer", true, "6789", "individual");
        String id = bill(v, "2026-09-01", "net_30", "1200.00", "6040");
        JsonNode approved = api.post(base + "/bills/" + id + "/approve", Map.of(), HttpStatus.OK);
        assertThat(approved.get("status").asText()).isEqualTo("open");
        assertThat(approved.get("dueDate").asText()).isEqualTo("2026-10-01");

        JsonNode entry = api.get(base + "/journal-entries/" + approved.get("journalEntryId").asText());
        assertThat(entry.get("source").asText()).isEqualTo("bill");
        assertThat(entry.get("lines").get(0).get("accountId").asText()).isEqualTo(acct.get("6040"));
        assertThat(amt(entry.get("lines").get(0).get("amount"))).isEqualTo("1200.00");
        assertThat(entry.get("lines").get(1).get("accountId").asText()).isEqualTo(acct.get("2100")); // A/P

        api.post(base + "/bills/" + id + "/approve", Map.of(), HttpStatus.CONFLICT);

        Map<String, Object> badLine = new HashMap<>();
        badLine.put("vendorId", v);
        badLine.put("billDate", "2026-09-01");
        badLine.put("terms", "net_30");
        badLine.put("lines", List.of(Map.of("description", "Income?", "amount", money("10.00"),
                "expenseAccountId", acct.get("4010"))));
        assertThat(api.post(base + "/bills", badLine, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("ACCOUNT_NOT_POSTABLE");
    }

    @Test
    void ac3_paymentsRespectBalanceAndUpdateStatus() {
        String v = vendor("Freelance Designer", true, "6789", "individual");
        String id = approvedBill(v, "2026-09-01", "net_30", "1200.00", "6040");

        pay(v, "2026-09-15", id, "500.00");
        JsonNode partial = api.get(base + "/bills/" + id);
        assertThat(partial.get("status").asText()).isEqualTo("partially_paid");
        assertThat(amt(partial.get("balanceDue"))).isEqualTo("700.00");

        assertThat(api.post(base + "/bill-payments", Map.of("vendorId", v, "paidDate", "2026-09-20",
                        "paymentAccountId", acct.get("1010"),
                        "applications", List.of(Map.of("billId", id, "amount", money("800.00")))), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("OVERPAYMENT");

        pay(v, "2026-09-20", id, "700.00");
        assertThat(api.get(base + "/bills/" + id).get("status").asText()).isEqualTo("paid");

        // paying from a credit card is allowed
        String card = approvedBill(v, "2026-10-01", "net_15", "100.00", "6040");
        api.post(base + "/bill-payments", Map.of("vendorId", v, "paidDate", "2026-10-05",
                "paymentAccountId", acct.get("2010"),
                "applications", List.of(Map.of("billId", card, "amount", money("100.00")))), HttpStatus.CREATED);
    }

    @Test
    void ac1_voidAndLockedPeriods() {
        String v = vendor("Supplies Co", false, null, null);
        String unpaid = approvedBill(v, "2026-09-01", "net_30", "60.00", "6160");
        assertThat(api.post(base + "/bills/" + unpaid + "/void", Map.of(), HttpStatus.OK).get("status").asText())
                .isEqualTo("void");

        String paidBill = approvedBill(v, "2026-09-02", "net_30", "40.00", "6160");
        pay(v, "2026-09-03", paidBill, "40.00");
        assertThat(api.post(base + "/bills/" + paidBill + "/void", Map.of(), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("BILL_HAS_PAYMENTS");

        String draft = bill(v, "2026-05-01", "net_30", "25.00", "6160");
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-06-30"));
        assertThat(api.post(base + "/bills/" + draft + "/approve", Map.of(), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("PERIOD_LOCKED");
    }

    @Test
    void ac4_payableAgingMatchesTrialBalance() {
        String v = vendor("Supplies Co", false, null, null);
        approvedBill(v, "2026-09-20", "net_30", "100.00", "6160");   // due 2026-10-20 → current
        String old = approvedBill(v, "2026-06-01", "net_15", "250.00", "6160"); // due 2026-06-16 → 106 days late
        pay(v, "2026-09-25", old, "50.00");

        JsonNode aging = api.get(base + "/reports/accounts-payable-aging?asOf=2026-09-30");
        assertThat(amt(aging.get("totals").get("current"))).isEqualTo("100.00");
        assertThat(amt(aging.get("totals").get("days90plus"))).isEqualTo("200.00");
        assertThat(amt(aging.get("totals").get("total"))).isEqualTo("300.00");

        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-09-30");
        String payable = null;
        for (JsonNode row : tb.get("rows")) {
            if (row.get("code").asText().equals("2100")) {
                payable = amt(row.get("credit"));
            }
        }
        assertThat(payable).isEqualTo("300.00");
    }

    @Test
    void ac5_ac6_ac7_form1099UsesTheThresholdForTheYear() {
        String big = vendor("Contractor A", true, "1234", "individual");
        String small = vendor("Contractor B", true, null, null);
        String corporation = vendor("Big Corp Inc", false, null, null);

        String bigBill = approvedBill(big, "2026-02-01", "net_30", "2100.00", "6040");
        pay(big, "2026-03-01", bigBill, "2100.00");
        String smallBill = approvedBill(small, "2026-02-01", "net_30", "1500.00", "6040");
        pay(small, "2026-03-01", smallBill, "1500.00");
        String corpBill = approvedBill(corporation, "2026-02-01", "net_30", "5000.00", "6160");
        pay(corporation, "2026-03-01", corpBill, "5000.00");

        JsonNode report2026 = api.get(base + "/reports/form-1099-candidates?taxYear=2026");
        assertThat(report2026.get("thresholdKnown").asBoolean()).isTrue();
        assertThat(amt(report2026.get("threshold"))).isEqualTo("2000.00");
        assertThat(report2026.get("vendors")).hasSize(2); // the corporation is excluded

        JsonNode first = report2026.get("vendors").get(0);
        assertThat(first.get("vendorName").asText()).isEqualTo("Contractor A");
        assertThat(amt(first.get("paidInYear"))).isEqualTo("2100.00");
        assertThat(first.get("meetsThreshold").asBoolean()).isTrue();
        assertThat(first.get("missingInformation")).isEmpty();

        JsonNode second = report2026.get("vendors").get(1);
        assertThat(second.get("meetsThreshold").asBoolean()).isFalse();

        // 2025 rules would have caught the $1,500 vendor — but the payments happened in 2026.
        JsonNode report2025 = api.get(base + "/reports/form-1099-candidates?taxYear=2025");
        assertThat(amt(report2025.get("threshold"))).isEqualTo("600.00");
        assertThat(amt(report2025.get("vendors").get(0).get("paidInYear"))).isEqualTo("0.00");

        JsonNode unknown = api.get(base + "/reports/form-1099-candidates?taxYear=2031");
        assertThat(unknown.get("thresholdKnown").asBoolean()).isFalse();
        assertThat(unknown.get("note").asText()).contains("will not judge");
        assertThat(unknown.get("vendors").get(0).get("meetsThreshold").asBoolean()).isFalse();
    }

    @Test
    void ac6_missingW9InformationIsFlagged() {
        String v = vendor("Contractor C", true, null, null);
        String id = approvedBill(v, "2026-02-01", "net_30", "3000.00", "6040");
        pay(v, "2026-02-15", id, "3000.00");

        JsonNode report = api.get(base + "/reports/form-1099-candidates?taxYear=2026");
        JsonNode candidate = report.get("vendors").get(0);
        assertThat(candidate.get("meetsThreshold").asBoolean()).isTrue();
        assertThat(candidate.get("missingInformation").toString()).contains("Taxpayer ID").contains("Tax classification");
    }

    @Test
    void ac8_viewerCannotCreateVendors() {
        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get(base + "/vendors", HttpStatus.OK);
        viewer.post(base + "/vendors", Map.of("name", "Nope"), HttpStatus.FORBIDDEN);
    }
}
