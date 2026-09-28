package com.aesoftwaresolutions.solid.reporting;

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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec 067 — the quarterly set-aside worksheet, presented as numbered steps. Golden numbers are hand-computed
 * in the spec's acceptance criteria and mirrored here, not recomputed by another copy of the formula.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class EstimatedTaxApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;
    ApiClient admin;
    String baseMap;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        baseMap = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(baseMap + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));

        admin = new ApiClient(rest);
        jdbc.update("update iam.user_account set is_instance_admin = true where email = ?", admin.email());
    }

    private void profit(String amount, String year) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", year + "-06-15");
        body.put("post", true);
        body.put("lines", List.of(
                Map.of("accountId", acct.get("1010"), "amount", Map.of("amount", "-" + amount, "currency", "USD")),
                Map.of("accountId", acct.get("4010"), "amount", Map.of("amount", amount, "currency", "USD"))));
        api.post(baseMap + "/journal-entries", body, HttpStatus.CREATED);
    }

    private JsonNode worksheet(int year, String query) {
        return api.get(baseMap + "/reports/estimated-tax?taxYear=" + year + query);
    }

    private static JsonNode step(JsonNode w, String labelStartsWith) {
        for (JsonNode step : w.get("steps")) {
            if (step.get("label").asText().startsWith(labelStartsWith)) {
                return step;
            }
        }
        throw new AssertionError("no step like " + labelStartsWith + " in " + w.get("steps"));
    }

    private static String amt(JsonNode step) {
        return step.get("amount").get("amount").asText();
    }

    @Test
    void ac1_ac2_theWorksheetReadsLineByLineLikeThePaperOne() {
        profit("2000.00", "2026");
        JsonNode w = worksheet(2026, "&marginalRatePercent=24");

        assertThat(amt(step(w, "Net profit"))).isEqualTo("2000.00");
        assertThat(amt(step(w, "Self-employment earnings"))).isEqualTo("1847.00");
        assertThat(step(w, "Self-employment earnings").get("note").asText()).contains("92.35%");
        assertThat(amt(step(w, "Social Security part"))).isEqualTo("229.03");
        assertThat(amt(step(w, "Medicare part"))).isEqualTo("53.56");
        assertThat(amt(step(w, "Self-employment tax"))).isEqualTo("282.59");
        assertThat(amt(step(w, "Half of that is deductible"))).isEqualTo("141.30");
        assertThat(amt(step(w, "Income tax at the rate you chose"))).isEqualTo("446.09");
        assertThat(step(w, "Income tax at the rate you chose").get("note").asText()).contains("24%");

        assertThat(w.get("selfEmploymentTax").get("amount").asText()).isEqualTo("282.59");
        assertThat(w.get("incomeTaxIncluded").asBoolean()).isTrue();
        assertThat(w.get("incomeTaxEstimate").get("amount").asText()).isEqualTo("446.09");
        assertThat(w.get("annualSetAside").get("amount").asText()).isEqualTo("728.68");
        assertThat(w.get("quarterlyPayment").get("amount").asText()).isEqualTo("182.17");
        assertThat(w.get("wageBaseKnown").asBoolean()).isTrue();
        assertThat(w.get("wageBaseSource").asText()).contains("ssa.gov");
        assertThat(w.get("quarterlyDueDates")).hasSize(4);
        assertThat(w.get("quarterlyDueDates").get(3).asText()).contains("January 15, 2027");
    }

    @Test
    void ac3_withoutARateTheWorksheetStopsAndSaysWhy() {
        profit("2000.00", "2026");
        JsonNode w = worksheet(2026, "");

        assertThat(w.get("incomeTaxIncluded").asBoolean()).isFalse();
        assertThat(w.get("incomeTaxEstimate").get("amount").asText()).isEqualTo("0.00");
        JsonNode incomeTax = step(w, "Income tax");
        assertThat(incomeTax.get("amount").isNull()).isTrue();
        assertThat(incomeTax.get("note").asText()).contains("will not pick");
    }

    @Test
    void ac4_belowThe400ThresholdTheWorksheetSaysNothingIsDue() {
        profit("200.00", "2026");
        JsonNode w = worksheet(2026, "&marginalRatePercent=24");

        assertThat(amt(step(w, "Self-employment tax"))).isEqualTo("0.00");
        assertThat(step(w, "Self-employment tax").get("note").asText()).contains("6017");
        assertThat(w.get("quarterlyPayment").get("amount").asText()).isEqualTo("0.00");
    }

    @Test
    void ac4b_noProfitAtAllIsAOneLineAnswer() {
        profit("0.00", "2026");
        JsonNode w = worksheet(2026, "");

        assertThat(amt(step(w, "Nothing to set aside"))).isEqualTo("0.00");
        assertThat(w.get("quarterlyPayment").get("amount").asText()).isEqualTo("0.00");
    }

    @Test
    void ac5_overTheWageBaseTheCapIsAppliedAndNamed() {
        profit("300000.00", "2026");
        JsonNode w = worksheet(2026, "");

        assertThat(amt(step(w, "Social Security part"))).isEqualTo("22878.00");
        assertThat(step(w, "Social Security part").get("note").asText()).contains("cap");
        assertThat(amt(step(w, "Medicare part"))).isEqualTo("8034.45");
    }

    @Test
    void ac6_ayearWithNoWageBaseSaysSoRightOnTheStep() {
        profit("300000.00", "2099");
        JsonNode w = worksheet(2099, "");

        assertThat(w.get("wageBaseKnown").asBoolean()).as("nothing interpolated").isFalse();
        assertThat(step(w, "Social Security part").get("note").asText()).contains("NO wage-base cap");
        assertThat(amt(step(w, "Social Security part"))).isEqualTo("34354.20");
    }

    @Test
    void ac7_aRuntimeWageBaseWinsOverTheFile() {
        jdbc.update("delete from tax.figure");
        profit("300000.00", "2026");
        admin.post("/api/v1/instance/tax-figures", Map.of("key", "se_wage_base", "taxYear", 2026,
                "value", "190000.00", "source",
                "SSA hypothetical notice for the test fixture: 2026 wage base set to 190,000.00 as printed."),
                HttpStatus.OK);

        JsonNode w = worksheet(2026, "");

        assertThat(w.get("wageBaseSource").asText()).contains("Added on this installation");
        assertThat(amt(step(w, "Social Security part"))).isEqualTo("23560.00");
    }

    @Test
    void ac8_aNonsenseRateIsRefusedRatherThanUsed() {
        profit("2000.00", "2026");

        api.get(baseMap + "/reports/estimated-tax?taxYear=2026&marginalRatePercent=104", HttpStatus.BAD_REQUEST);
        api.get(baseMap + "/reports/estimated-tax?taxYear=2026&marginalRatePercent=-5", HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac9_onlyMembersReadItAndViewersMay() {
        ApiClient viewer = new ApiClient(rest);
        String orgId = api.get("/api/v1/orgs").get(0).get("id").asText();
        api.post("/api/v1/orgs/" + orgId + "/members",
                Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        profit("2000.00", "2026");

        viewer.get(baseMap + "/reports/estimated-tax?taxYear=2026", HttpStatus.OK);
        new ApiClient(rest, false).get(baseMap + "/reports/estimated-tax?taxYear=2026", HttpStatus.UNAUTHORIZED);
    }
}
