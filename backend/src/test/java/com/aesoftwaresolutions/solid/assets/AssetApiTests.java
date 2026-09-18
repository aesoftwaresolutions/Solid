package com.aesoftwaresolutions.solid.assets;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 014. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AssetApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private JsonNode createAsset(String name, String placed, String cost, int months) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("placedInServiceDate", placed);
        body.put("cost", money(cost));
        body.put("usefulLifeMonths", months);
        body.put("assetAccountId", acct.get("1500"));
        body.put("accumulatedAccountId", acct.get("1510"));
        body.put("depreciationExpenseAccountId", acct.get("6050"));
        return api.post(base + "/assets", body, HttpStatus.CREATED);
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    @Test
    void ac1_monthlyAmountsAllocateExactly() {
        JsonNode asset = createAsset("Laptop", "2026-10-01", "1200.00", 36);
        JsonNode schedule = asset.get("monthlySchedule");
        assertThat(schedule).hasSize(36);
        assertThat(amt(schedule.get(0).get("amount"))).isEqualTo("33.33");
        assertThat(amt(schedule.get(35).get("amount"))).as("leftover cents land at the end").isEqualTo("33.34");
        assertThat(schedule.get(0).get("month").asText()).isEqualTo("2026-10-01");

        long totalCents = 0;
        for (JsonNode month : schedule) {
            totalCents += Math.round(Double.parseDouble(amt(month.get("amount"))) * 100);
        }
        assertThat(totalCents).isEqualTo(120000L);
    }

    @Test
    void ac2_ac3_ac4_runPostsMonthlyEntriesOnceAndKeepsBooksBalanced() {
        String assetId = createAsset("Laptop", "2026-10-01", "1200.00", 36).get("id").asText();

        JsonNode run = api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-12"), HttpStatus.CREATED);
        assertThat(run.get("months")).hasSize(3);
        assertThat(run.get("months").get(0).get("month").asText()).isEqualTo("2026-10-01");
        assertThat(amt(run.get("totalPosted"))).isEqualTo("99.99");

        JsonNode entry = api.get(base + "/journal-entries/" + run.get("months").get(0).get("journalEntryId").asText());
        assertThat(entry.get("entryDate").asText()).isEqualTo("2026-10-31");
        assertThat(entry.get("source").asText()).isEqualTo("depreciation");
        assertThat(entry.get("lines").get(0).get("accountId").asText()).isEqualTo(acct.get("6050"));
        assertThat(amt(entry.get("lines").get(0).get("amount"))).isEqualTo("33.33");

        JsonNode again = api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-12"), HttpStatus.CREATED);
        assertThat(again.get("months")).isEmpty();
        assertThat(amt(again.get("totalPosted"))).isEqualTo("0.00");

        JsonNode asset = api.get(base + "/assets/" + assetId);
        assertThat(amt(asset.get("accumulatedDepreciation"))).isEqualTo("99.99");
        assertThat(amt(asset.get("netBookValue"))).isEqualTo("1100.01");

        JsonNode report = api.get(base + "/reports/fixed-assets?asOf=2026-12-31");
        assertThat(amt(report.get("totalCost"))).isEqualTo("1200.00");
        assertThat(amt(report.get("totalAccumulated"))).isEqualTo("99.99");
        assertThat(amt(report.get("totalNetBookValue"))).isEqualTo("1100.01");
        assertThat(report.get("taxNote").asText()).contains("MACRS");

        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-12-31");
        assertThat(tb.get("totalDebit")).isEqualTo(tb.get("totalCredit"));
    }

    @Test
    void ac5_ac6_disposalPostsGainOrLossAndBlocksSecondDisposal() {
        String assetId = createAsset("Laptop", "2026-10-01", "1200.00", 36).get("id").asText();
        api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-12"), HttpStatus.CREATED);

        JsonNode disposed = api.post(base + "/assets/" + assetId + "/dispose", Map.of(
                "disposalDate", "2027-01-05", "proceeds", money("1000.00"),
                "depositAccountId", acct.get("1010"), "gainLossAccountId", acct.get("4900")), HttpStatus.OK);
        assertThat(disposed.get("status").asText()).isEqualTo("disposed");

        JsonNode entries = api.get(base + "/journal-entries?from=2027-01-01&to=2027-01-31");
        JsonNode entry = entries.get(0);
        assertThat(entry.get("source").asText()).isEqualTo("asset_disposal");
        // Net book value 1,100.01 sold for 1,000.00 → loss of 100.01 debited to the gain/loss account
        boolean lossPosted = false;
        for (JsonNode line : entry.get("lines")) {
            if (line.get("accountId").asText().equals(acct.get("4900"))) {
                assertThat(amt(line.get("amount"))).isEqualTo("100.01");
                lossPosted = true;
            }
        }
        assertThat(lossPosted).isTrue();

        api.post(base + "/assets/" + assetId + "/dispose", Map.of("disposalDate", "2027-02-01",
                "gainLossAccountId", acct.get("4900")), HttpStatus.CONFLICT);
        JsonNode run = api.post(base + "/depreciation-runs", Map.of("throughMonth", "2027-06"), HttpStatus.CREATED);
        assertThat(run.get("months")).isEmpty();

        assertThat(api.get(base + "/reports/fixed-assets?asOf=2027-12-31").get("assets")).isEmpty();
    }

    @Test
    void ac5_gainOnDisposalCreditsIncome() {
        String assetId = createAsset("Camera", "2026-01-01", "600.00", 12).get("id").asText();
        api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-06"), HttpStatus.CREATED);
        // 6 months of 50.00 = 300.00 accumulated, net book value 300.00
        JsonNode disposed = api.post(base + "/assets/" + assetId + "/dispose", Map.of(
                "disposalDate", "2026-07-01", "proceeds", money("450.00"),
                "depositAccountId", acct.get("1010"), "gainLossAccountId", acct.get("4900")), HttpStatus.OK);
        assertThat(disposed.get("status").asText()).isEqualTo("disposed");

        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-07-01&to=2026-07-31");
        assertThat(amt(pl.get("income").get("total"))).isEqualTo("150.00");
    }

    @Test
    void ac7_lockedMonthsAreSkippedAndReported() {
        createAsset("Laptop", "2026-01-01", "1200.00", 36);
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-03-31"));

        JsonNode run = api.post(base + "/depreciation-runs", Map.of("throughMonth", "2026-06"), HttpStatus.CREATED);
        assertThat(run.get("skippedMonths")).hasSize(3);
        assertThat(run.get("months")).hasSize(3);
        assertThat(run.get("skippedReason").asText()).contains("locked");
    }

    @Test
    void ac8_invalidInputsAreRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Bad");
        body.put("placedInServiceDate", "2026-01-01");
        body.put("cost", money("100.00"));
        body.put("salvageValue", money("100.00"));
        body.put("usefulLifeMonths", 12);
        body.put("assetAccountId", acct.get("1500"));
        body.put("accumulatedAccountId", acct.get("1510"));
        body.put("depreciationExpenseAccountId", acct.get("6050"));
        api.post(base + "/assets", body, HttpStatus.BAD_REQUEST);

        body.put("salvageValue", money("0.00"));
        body.put("usefulLifeMonths", 0);
        api.post(base + "/assets", body, HttpStatus.BAD_REQUEST);

        body.put("usefulLifeMonths", 12);
        body.put("depreciationExpenseAccountId", acct.get("1010")); // not an expense account
        assertThat(api.post(base + "/assets", body, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("ACCOUNT_NOT_POSTABLE");
    }
}
