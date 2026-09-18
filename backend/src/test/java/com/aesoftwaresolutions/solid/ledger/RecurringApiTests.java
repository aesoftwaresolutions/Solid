package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
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

/** Spec 026. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RecurringApiTests {

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

    private Map<String, Object> template(String name, String frequency, String startDate, Integer dayOfMonth,
                                         String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("frequency", frequency);
        body.put("startDate", startDate);
        body.put("dayOfMonth", dayOfMonth);
        body.put("lines", List.of(
                Map.of("accountId", acct.get("6010"), "amount", money(amount)),
                Map.of("accountId", acct.get("1010"), "amount", money("-" + amount))));
        return body;
    }

    private List<String> run(String through) {
        JsonNode result = api.post(base + "/recurring-entries/run", Map.of("through", through), HttpStatus.OK);
        List<String> dates = new ArrayList<>();
        result.get("posted").forEach(p -> dates.add(p.get("occurrenceDate").asText()));
        return dates;
    }

    @Test
    void ac1_ac2_ac3_postsEachDueMonthExactlyOnce() {
        api.post(base + "/recurring-entries", template("Rent", "monthly", "2026-01-15", 15, "1200.00"),
                HttpStatus.CREATED);

        assertThat(run("2026-03-31")).containsExactly("2026-01-15", "2026-02-15", "2026-03-15");
        assertThat(run("2026-03-31")).as("running the same range again changes nothing").isEmpty();
        assertThat(run("2026-05-20")).containsExactly("2026-04-15", "2026-05-15");

        JsonNode entries = api.get(base + "/journal-entries?from=2026-01-01&to=2026-12-31");
        long fromRecurring = 0;
        for (JsonNode entry : entries) {
            if (entry.get("source").asText().equals("recurring")) {
                fromRecurring++;
                assertThat(entry.get("status").asText()).isEqualTo("posted");
            }
        }
        assertThat(fromRecurring).isEqualTo(5);
    }

    @Test
    void ac4_theThirtyFirstBecomesTheLastDayOfAShortMonth() {
        api.post(base + "/recurring-entries", template("Loan", "monthly", "2026-01-31", 31, "100.00"),
                HttpStatus.CREATED);

        assertThat(run("2026-07-01")).containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30",
                "2026-05-31", "2026-06-30");
    }

    @Test
    void ac5_badTemplatesAreRefused() {
        Map<String, Object> unbalanced = template("Broken", "monthly", "2026-01-15", 15, "100.00");
        unbalanced.put("lines", List.of(
                Map.of("accountId", acct.get("6010"), "amount", money("100.00")),
                Map.of("accountId", acct.get("1010"), "amount", money("-90.00"))));
        JsonNode refused = api.post(base + "/recurring-entries", unbalanced, HttpStatus.CONFLICT);
        assertThat(refused.get("code").asText()).isEqualTo("ENTRY_NOT_BALANCED");

        Map<String, Object> wrongCurrency = template("Euro", "monthly", "2026-01-15", 15, "100.00");
        wrongCurrency.put("lines", List.of(
                Map.of("accountId", acct.get("6010"), "amount", Map.of("amount", "100.00", "currency", "EUR")),
                Map.of("accountId", acct.get("1010"), "amount", Map.of("amount", "-100.00", "currency", "EUR"))));
        api.post(base + "/recurring-entries", wrongCurrency, HttpStatus.CONFLICT);

        Map<String, Object> header = template("Header", "monthly", "2026-01-15", 15, "100.00");
        header.put("lines", List.of(
                Map.of("accountId", acct.get("6000"), "amount", money("100.00")),
                Map.of("accountId", acct.get("1010"), "amount", money("-100.00"))));
        api.post(base + "/recurring-entries", header, HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac6_aLockedMonthIsSkippedAndTheRestStillPost() {
        api.post(base + "/recurring-entries", template("Rent", "monthly", "2026-01-15", 15, "1200.00"),
                HttpStatus.CREATED);
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-02-28"));

        JsonNode result = api.post(base + "/recurring-entries/run", Map.of("through", "2026-04-30"), HttpStatus.OK);

        List<String> skipped = new ArrayList<>();
        result.get("skipped").forEach(s -> skipped.add(s.get("occurrenceDate").asText()));
        List<String> posted = new ArrayList<>();
        result.get("posted").forEach(p -> posted.add(p.get("occurrenceDate").asText()));

        assertThat(skipped).containsExactly("2026-01-15", "2026-02-15");
        assertThat(result.get("skipped").get(0).get("reason").asText()).containsIgnoringCase("locked");
        assertThat(posted).containsExactly("2026-03-15", "2026-04-15");
    }

    @Test
    void ac7_deactivatingStopsItAndTheOtherFrequenciesStep() {
        String monthlyId = api.post(base + "/recurring-entries",
                template("Rent", "monthly", "2026-01-15", 15, "1200.00"), HttpStatus.CREATED).get("id").asText();
        api.post(base + "/recurring-entries", template("Insurance", "quarterly", "2026-01-10", 10, "300.00"),
                HttpStatus.CREATED);
        api.post(base + "/recurring-entries", template("Filing fee", "annual", "2026-02-01", 1, "75.00"),
                HttpStatus.CREATED);

        JsonNode stopped = api.post(base + "/recurring-entries/" + monthlyId + "/deactivate", Map.of(), HttpStatus.OK);
        assertThat(stopped.get("active").asBoolean()).isFalse();
        assertThat(stopped.get("nextDate").isNull()).isTrue();

        // Templates run in name order (Filing fee, Insurance, Rent), and Rent is now inactive.
        assertThat(run("2026-12-31")).containsExactly("2026-02-01", "2026-01-10", "2026-04-10", "2026-07-10",
                "2026-10-10");

        JsonNode quarterly = api.get(base + "/recurring-entries");
        for (JsonNode node : quarterly) {
            if (node.get("name").asText().equals("Insurance")) {
                assertThat(node.get("nextDate").asText()).isEqualTo("2027-01-10");
            }
        }
    }
}
