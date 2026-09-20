package com.aesoftwaresolutions.solid.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** Spec 038. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class EditPartiesApiTests {

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

    private String vendorPaid(String name, String amount) {
        String vendorId = api.post(base + "/vendors", Map.of("name", name, "is1099Vendor", true), HttpStatus.CREATED)
                .get("id").asText();
        String billId = api.post(base + "/bills", Map.of("vendorId", vendorId, "billDate", "2026-04-01",
                "terms", "net_30", "lines", List.of(Map.of("description", "Work", "amount", money(amount),
                        "expenseAccountId", acct.get("6010")))), HttpStatus.CREATED).get("id").asText();
        api.post(base + "/bills/" + billId + "/approve", Map.of(), HttpStatus.OK);
        api.post(base + "/bill-payments", Map.of("vendorId", vendorId, "paidDate", "2026-04-15",
                "paymentAccountId", acct.get("1010"),
                "applications", List.of(Map.of("billId", billId, "amount", money(amount)))), HttpStatus.CREATED);
        return vendorId;
    }

    private static JsonNode vendor(JsonNode vendors, String name) {
        for (JsonNode node : vendors) {
            if (node.get("name").asText().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("No vendor " + name);
    }

    @Test
    void ac1_ac2_fillingInWhatThe1099ReportAsksForClearsIt() {
        String vendorId = vendorPaid("Contractor Co", "3000.00");

        JsonNode before = api.get(base + "/reports/form-1099-candidates?taxYear=2026");
        assertThat(before.get("vendors").get(0).get("missingInformation")).isNotEmpty();

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("taxIdLast4", "6789");
        patch.put("taxClassification", "single_member_llc");
        JsonNode updated = api.patch(base + "/vendors/" + vendorId, patch, HttpStatus.OK);

        assertThat(updated.get("taxIdLast4").asText()).isEqualTo("6789");
        assertThat(updated.get("taxClassification").asText()).isEqualTo("single_member_llc");
        assertThat(updated.get("name").asText()).as("untouched fields stay").isEqualTo("Contractor Co");
        assertThat(updated.get("is1099Vendor").asBoolean()).isTrue();

        JsonNode after = api.get(base + "/reports/form-1099-candidates?taxYear=2026");
        assertThat(after.get("vendors").get(0).get("missingInformation")).isEmpty();
    }

    @Test
    void ac3_customerDetailsChangeAndShowOnAStatement() {
        String customerId = api.post(base + "/customers", Map.of("name", "Northwinde Traders"), HttpStatus.CREATED)
                .get("id").asText();

        JsonNode updated = api.patch(base + "/customers/" + customerId,
                Map.of("name", "Northwind Traders", "billingAddress", "1 Example Way"), HttpStatus.OK);
        assertThat(updated.get("name").asText()).isEqualTo("Northwind Traders");
        assertThat(updated.get("billingAddress").asText()).isEqualTo("1 Example Way");

        JsonNode statement = api.get(base + "/customers/" + customerId + "/statement");
        assertThat(statement.get("customer").get("name").asText()).isEqualTo("Northwind Traders");
    }

    @Test
    void ac4_archivingHidesAVendorWithoutTouchingItsBills() {
        String vendorId = vendorPaid("Old Supplier", "100.00");

        api.patch(base + "/vendors/" + vendorId, Map.of("archived", true), HttpStatus.OK);
        assertThat(vendor(api.get(base + "/vendors"), "Old Supplier").get("isArchived").asBoolean()).isTrue();
        assertThat(api.get(base + "/bills")).hasSize(1);

        api.patch(base + "/vendors/" + vendorId, Map.of("archived", false), HttpStatus.OK);
        assertThat(vendor(api.get(base + "/vendors"), "Old Supplier").get("isArchived").asBoolean()).isFalse();
    }

    @Test
    void ac5_badValuesAreRefused() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Contractor Co"), HttpStatus.CREATED)
                .get("id").asText();

        api.patch(base + "/vendors/" + vendorId, Map.of("name", "   "), HttpStatus.BAD_REQUEST);
        api.patch(base + "/vendors/" + vendorId, Map.of("email", "not-an-email"), HttpStatus.BAD_REQUEST);
        api.patch(base + "/vendors/" + vendorId, Map.of("taxIdLast4", "123456789"), HttpStatus.BAD_REQUEST);
        api.patch(base + "/vendors/" + vendorId, Map.of("taxClassification", "wizard"), HttpStatus.BAD_REQUEST);
        api.patch(base + "/vendors/" + vendorId, Map.of("defaultExpenseAccountId", acct.get("1010")),
                HttpStatus.CONFLICT);

        String other = api.newEntity(org, "sole_prop");
        Map<String, String> otherAcct = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + other + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> otherAcct.put(a.get("code").asText(), a.get("id").asText()));
        api.patch(base + "/vendors/" + vendorId, Map.of("defaultExpenseAccountId", otherAcct.get("6010")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void ac6_ac7_theAuditRecordsTheFieldsAndNotTheValues() {
        String vendorId = api.post(base + "/vendors", Map.of("name", "Contractor Co"), HttpStatus.CREATED)
                .get("id").asText();
        api.patch(base + "/vendors/" + vendorId, Map.of("taxIdLast4", "6789", "address", "5 Secret Lane"),
                HttpStatus.OK);

        JsonNode events = api.get("/api/v1/orgs/" + org + "/audit-events");
        JsonNode update = null;
        for (JsonNode event : events) {
            if (event.get("action").asText().equals("vendor_updated")) {
                update = event;
            }
        }
        assertThat(update).isNotNull();
        assertThat(update.get("details").toString()).contains("taxIdLast4").contains("address");
        assertThat(update.get("details").toString()).doesNotContain("6789").doesNotContain("Secret Lane");

        new ApiClient(rest).patch(base + "/vendors/" + vendorId, Map.of("name", "Hijack"), HttpStatus.NOT_FOUND);
    }

    @Test
    void clearingAFieldIsDifferentFromLeavingItAlone() {
        String vendorId = api.post(base + "/vendors",
                Map.of("name", "Contractor Co", "phone", "555-0100", "email", "hi@example.test"),
                HttpStatus.CREATED).get("id").asText();

        Map<String, Object> clearPhone = new java.util.HashMap<>();
        clearPhone.put("phone", null);
        JsonNode updated = api.patch(base + "/vendors/" + vendorId, clearPhone, HttpStatus.OK);

        assertThat(updated.get("phone").isNull()).as("an explicit null clears").isTrue();
        assertThat(updated.get("email").asText()).as("an absent field is left alone").isEqualTo("hi@example.test");
    }
}
