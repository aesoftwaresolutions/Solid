package com.aesoftwaresolutions.solid.ledger;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class JournalApiTests {

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

    static Map<String, Object> line(String accountId, String amount) {
        return Map.of("accountId", accountId, "amount", Map.of("amount", amount, "currency", "USD"));
    }

    Map<String, Object> entry(String date, boolean post, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("memo", "Test entry");
        body.put("post", post);
        body.put("lines", lines);
        return body;
    }

    /** $54.99 software subscription paid from checking. */
    Map<String, Object> software(String date, boolean post) {
        return entry(date, post, List.of(line(acct.get("6220"), "54.99"), line(acct.get("1010"), "-54.99")));
    }

    @Test
    void ac1_postsBalancedEntry() {
        JsonNode e = api.post(base + "/journal-entries", software("2026-09-10", true), HttpStatus.CREATED);
        assertThat(e.get("status").asText()).isEqualTo("posted");
        assertThat(e.get("postingSeq").asLong()).isEqualTo(1);
        assertThat(e.get("hash").asText()).hasSize(64);
        assertThat(e.get("lines")).hasSize(2);
        assertThat(e.get("lines").get(0).get("amount").get("amount").asText()).isEqualTo("54.99");
    }

    @Test
    void ac1_rejectsUnbalancedTooFewAndZeroLines() {
        JsonNode unbalanced = api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(acct.get("6220"), "54.99"), line(acct.get("1010"), "-54.98"))),
                HttpStatus.CONFLICT);
        assertThat(unbalanced.get("code").asText()).isEqualTo("UNBALANCED");
        assertThat(unbalanced.get("detail").asText()).contains("0.01");

        JsonNode single = api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(acct.get("6220"), "54.99"))), HttpStatus.CONFLICT);
        assertThat(single.get("code").asText()).isIn("TOO_FEW_LINES", "UNBALANCED");

        api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(acct.get("6220"), "0.00"), line(acct.get("1010"), "0.00"))),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac1_draftsMayBeUnbalancedButCannotBePostedThatWay() {
        JsonNode draft = api.post(base + "/journal-entries",
                entry("2026-09-10", false, List.of(line(acct.get("6220"), "10.00"))), HttpStatus.CREATED);
        assertThat(draft.get("status").asText()).isEqualTo("draft");
        assertThat(draft.get("postingSeq").isNull()).isTrue();

        api.post(base + "/journal-entries/" + draft.get("id").asText() + "/post", null, HttpStatus.CONFLICT);

        JsonNode balancedDraft = api.post(base + "/journal-entries", software("2026-09-11", false), HttpStatus.CREATED);
        JsonNode posted = api.post(base + "/journal-entries/" + balancedDraft.get("id").asText() + "/post", null,
                HttpStatus.OK);
        assertThat(posted.get("status").asText()).isEqualTo("posted");
    }

    @Test
    void ac2_accountsMustBePostableAndCurrencyMustMatch() {
        JsonNode header = api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(acct.get("6000"), "5.00"), line(acct.get("1010"), "-5.00"))),
                HttpStatus.CONFLICT);
        assertThat(header.get("code").asText()).isEqualTo("ACCOUNT_NOT_POSTABLE");

        String otherEntity = api.newEntity(org, "sole_prop");
        Map<String, String> otherAccts = new HashMap<>();
        api.post("/api/v1/orgs/" + org + "/entities/" + otherEntity + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> otherAccts.put(a.get("code").asText(), a.get("id").asText()));
        api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(otherAccts.get("6220"), "5.00"), line(acct.get("1010"), "-5.00"))),
                HttpStatus.CONFLICT);

        Map<String, Object> eur = Map.of("accountId", acct.get("6220"), "amount", Map.of("amount", "5.00", "currency", "EUR"));
        Map<String, Object> eur2 = Map.of("accountId", acct.get("1010"), "amount", Map.of("amount", "-5.00", "currency", "EUR"));
        JsonNode currency = api.post(base + "/journal-entries", entry("2026-09-10", true, List.of(eur, eur2)),
                HttpStatus.CONFLICT);
        assertThat(currency.get("code").asText()).isEqualTo("CURRENCY_MISMATCH");

        api.patch(base + "/accounts/" + acct.get("6900"), Map.of("archived", true), HttpStatus.OK);
        api.post(base + "/journal-entries",
                entry("2026-09-10", true, List.of(line(acct.get("6900"), "5.00"), line(acct.get("1010"), "-5.00"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void ac3_postedEntriesCannotBeDeletedButDraftsCan() {
        String posted = api.post(base + "/journal-entries", software("2026-09-10", true), HttpStatus.CREATED)
                .get("id").asText();
        ResponseEntity<JsonNode> del = rest.exchange(base + "/journal-entries/" + posted, HttpMethod.DELETE, null, JsonNode.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(del.getBody().get("code").asText()).isEqualTo("ENTRY_POSTED");

        String draft = api.post(base + "/journal-entries", software("2026-09-10", false), HttpStatus.CREATED)
                .get("id").asText();
        assertThat(rest.exchange(base + "/journal-entries/" + draft, HttpMethod.DELETE, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        api.get(base + "/journal-entries/" + draft, HttpStatus.NOT_FOUND);
    }

    @Test
    void ac5_periodLockBlocksPostingOnOrBeforeLockDate() {
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-06-30"));
        assertThat(api.get(base + "/period-lock").get("lockedThrough").asText()).isEqualTo("2026-06-30");

        JsonNode locked = api.post(base + "/journal-entries", software("2026-06-30", true), HttpStatus.CONFLICT);
        assertThat(locked.get("code").asText()).isEqualTo("PERIOD_LOCKED");
        api.post(base + "/journal-entries", software("2026-07-01", true), HttpStatus.CREATED);

        // Reopen by moving the lock back.
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-03-31"));
        api.post(base + "/journal-entries", software("2026-06-30", true), HttpStatus.CREATED);
    }

    @Test
    void ac6_reversalNegatesLinesOnceOnly() {
        JsonNode original = api.post(base + "/journal-entries", software("2026-09-10", true), HttpStatus.CREATED);
        String id = original.get("id").asText();

        JsonNode reversal = api.post(base + "/journal-entries/" + id + "/reverse", Map.of(), HttpStatus.CREATED);
        assertThat(reversal.get("reversesEntryId").asText()).isEqualTo(id);
        assertThat(reversal.get("entryDate").asText()).isEqualTo("2026-09-10");
        assertThat(reversal.get("status").asText()).isEqualTo("posted");
        assertThat(reversal.get("lines").get(0).get("amount").get("amount").asText()).isEqualTo("-54.99");
        assertThat(reversal.get("lines").get(1).get("amount").get("amount").asText()).isEqualTo("54.99");
        assertThat(reversal.get("postingSeq").asLong()).isEqualTo(2);

        JsonNode again = api.post(base + "/journal-entries/" + id + "/reverse", Map.of(), HttpStatus.CONFLICT);
        assertThat(again.get("code").asText()).isEqualTo("ALREADY_REVERSED");

        String draft = api.post(base + "/journal-entries", software("2026-09-10", false), HttpStatus.CREATED)
                .get("id").asText();
        assertThat(api.post(base + "/journal-entries/" + draft + "/reverse", Map.of(), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("NOT_POSTED");
    }

    @Test
    void ac6_reversalIntoLockedPeriodIsRejected() {
        String id = api.post(base + "/journal-entries", software("2026-05-10", true), HttpStatus.CREATED).get("id").asText();
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-06-30"));
        assertThat(api.post(base + "/journal-entries/" + id + "/reverse", Map.of(), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("PERIOD_LOCKED");
        api.post(base + "/journal-entries/" + id + "/reverse", Map.of("entryDate", "2026-07-01"), HttpStatus.CREATED);
    }

    @Test
    void ac7_hashChainVerifies() {
        for (int day = 1; day <= 5; day++) {
            api.post(base + "/journal-entries", software("2026-09-0" + day, true), HttpStatus.CREATED);
        }
        JsonNode result = api.get(base + "/journal/verify");
        assertThat(result.get("valid").asBoolean()).isTrue();
        assertThat(result.get("postedEntries").asLong()).isEqualTo(5);
    }

    @Test
    void ac8_idempotencyKeyPreventsDuplicates() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "bank-txn-123");
        ResponseEntity<JsonNode> first = rest.exchange(base + "/journal-entries", HttpMethod.POST,
                new HttpEntity<>(software("2026-09-10", true), headers), JsonNode.class);
        ResponseEntity<JsonNode> second = rest.exchange(base + "/journal-entries", HttpMethod.POST,
                new HttpEntity<>(software("2026-09-10", true), headers), JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(api.get(base + "/journal-entries?status=posted")).hasSize(1);
    }

    @Test
    void listFiltersByDate() {
        api.post(base + "/journal-entries", software("2026-01-15", true), HttpStatus.CREATED);
        api.post(base + "/journal-entries", software("2026-02-15", true), HttpStatus.CREATED);
        assertThat(api.get(base + "/journal-entries?from=2026-02-01&to=2026-02-28")).hasSize(1);
    }
}
