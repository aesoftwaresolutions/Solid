package com.aesoftwaresolutions.solid.budget;

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

/** Spec 019. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BudgetApiTests {

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
        String entity = api.newEntity(org, "individual");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "personal"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private static String amt(JsonNode node) {
        return node.get("amount").asText();
    }

    private Map<String, Object> line(String code, String amount) {
        return Map.of("accountId", acct.get(code), "amount", money(amount));
    }

    private JsonNode putBudget(String month, List<Map<String, Object>> lines, HttpStatus expected) {
        return api.put2(base + "/budgets/" + month, Map.of("lines", lines), expected);
    }

    private void post(String date, String debitCode, String creditCode, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", true);
        body.put("lines", List.of(
                Map.of("accountId", acct.get(debitCode), "amount", money(amount)),
                Map.of("accountId", acct.get(creditCode), "amount", money("-" + amount))));
        api.post(base + "/journal-entries", body, HttpStatus.CREATED);
    }

    private static Map<String, JsonNode> byCode(JsonNode rows) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.get("code").asText(), row));
        return result;
    }

    @Test
    void ac1_thePersonalTemplateIsForHouseholdsOnly() {
        assertThat(acct).containsKeys("1010", "4010", "6100", "6510");

        String business = api.newEntity(org, "sole_prop");
        api.post("/api/v1/orgs/" + org + "/entities/" + business + "/accounts/apply-template",
                Map.of("template", "personal"), HttpStatus.CONFLICT);
    }

    @Test
    void ac2_ac3_savingTwiceReplacesTheMonthsLines() {
        putBudget("2026-10", List.of(line("4010", "5000.00"), line("6100", "800.00")), HttpStatus.OK);
        JsonNode saved = api.get(base + "/budgets/2026-10");
        assertThat(saved.get("lines")).hasSize(2);
        assertThat(byCode(saved.get("lines")).get("6100").get("name").asText()).isEqualTo("Groceries");
        assertThat(amt(saved.get("budgetedIncome"))).isEqualTo("5000.00");
        assertThat(amt(saved.get("budgetedNet"))).isEqualTo("4200.00");

        putBudget("2026-10", List.of(line("4010", "5200.00"), line("6110", "150.00")), HttpStatus.OK);
        JsonNode again = api.get(base + "/budgets/2026-10");
        assertThat(byCode(again.get("lines")).keySet()).containsExactly("4010", "6110");
        assertThat(amt(byCode(again.get("lines")).get("4010").get("amount"))).isEqualTo("5200.00");
    }

    @Test
    void ac4_onlyThisEntitysPostableIncomeAndExpenseAccountsCanBeBudgeted() {
        putBudget("2026-10", List.of(line("6000", "100.00")), HttpStatus.BAD_REQUEST);   // header
        putBudget("2026-10", List.of(line("1010", "100.00")), HttpStatus.BAD_REQUEST);   // balance sheet

        String other = api.newEntity(org, "individual");
        String otherAccounts = "/api/v1/orgs/" + org + "/entities/" + other + "/accounts/apply-template";
        String foreignAccountId = api.post(otherAccounts, Map.of("template", "personal"), HttpStatus.CREATED)
                .get(0).get("id").asText();
        api.put2(base + "/budgets/2026-10",
                Map.of("lines", List.of(Map.of("accountId", foreignAccountId, "amount", money("10.00")))),
                HttpStatus.BAD_REQUEST);

        api.patch(base + "/accounts/" + acct.get("6510"), Map.of("archived", true), HttpStatus.OK);
        putBudget("2026-10", List.of(line("6510", "20.00")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_ac6_ac7_comparesTheBudgetWithWhatActuallyHappened() {
        putBudget("2026-10", List.of(line("4010", "5000.00"), line("6100", "800.00"), line("6520", "300.00")),
                HttpStatus.OK);

        post("2026-10-01", "1010", "4010", "5200.00");   // paid more than planned
        post("2026-10-05", "6100", "1010", "912.34");    // groceries over budget
        post("2026-10-09", "6110", "1010", "60.00");     // dining out: never budgeted
        post("2026-11-02", "6100", "1010", "500.00");    // next month: must not count

        JsonNode report = api.get(base + "/reports/budget-vs-actual?month=2026-10");
        Map<String, JsonNode> income = byCode(report.get("income"));
        Map<String, JsonNode> expenses = byCode(report.get("expenses"));

        assertThat(amt(income.get("4010").get("actual"))).isEqualTo("5200.00");
        assertThat(amt(income.get("4010").get("variance"))).as("ahead of plan is positive").isEqualTo("200.00");
        assertThat(income.get("4010").get("overBudget").asBoolean()).isFalse();

        assertThat(amt(expenses.get("6100").get("actual"))).isEqualTo("912.34");
        assertThat(amt(expenses.get("6100").get("variance"))).as("overspending is negative").isEqualTo("-112.34");
        assertThat(expenses.get("6100").get("overBudget").asBoolean()).isTrue();

        // AC6: budgeted but unused, and used but unbudgeted, both show up.
        assertThat(amt(expenses.get("6520").get("actual"))).isEqualTo("0.00");
        assertThat(expenses.get("6520").get("overBudget").asBoolean()).isFalse();
        assertThat(amt(expenses.get("6110").get("budget"))).isEqualTo("0.00");
        assertThat(amt(expenses.get("6110").get("actual"))).isEqualTo("60.00");

        JsonNode totals = report.get("totals");
        assertThat(amt(totals.get("budgetedIncome"))).isEqualTo("5000.00");
        assertThat(amt(totals.get("actualIncome"))).isEqualTo("5200.00");
        assertThat(amt(totals.get("budgetedExpenses"))).isEqualTo("1100.00");
        assertThat(amt(totals.get("actualExpenses"))).isEqualTo("972.34");
        assertThat(amt(totals.get("budgetedNet"))).isEqualTo("3900.00");
        assertThat(amt(totals.get("actualNet"))).isEqualTo("4227.66");

        JsonNode pnl = api.get(base + "/reports/profit-and-loss?from=2026-10-01&to=2026-10-31");
        assertThat(amt(pnl.get("netIncome"))).as("the budget report and the P&L agree")
                .isEqualTo(amt(totals.get("actualNet")));
    }
}
