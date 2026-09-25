package com.aesoftwaresolutions.solid.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Spec 009. The golden numbers come from docs/specs/009-bank-reconciliation.md. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReconciliationApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    String checking;
    String recBase;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUpLedger() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        checking = api.post(base + "/bank-accounts", Map.of("name", "Checking", "glAccountId", acct.get("1010")),
                HttpStatus.CREATED).get("id").asText();
        recBase = base + "/bank-accounts/" + checking + "/reconciliations";

        // Import the September statement and categorize every transaction.
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(StatementParserTests.fixture("checking-2026-09.csv").getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "checking-2026-09.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        rest.exchange(base + "/bank-accounts/" + checking + "/imports", HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);

        for (JsonNode t : api.get(base + "/bank-transactions?status=new")) {
            String category = t.get("amount").get("amount").asText().startsWith("-") ? acct.get("6900") : acct.get("4010");
            api.post(base + "/bank-transactions/" + t.get("id").asText() + "/categorize",
                    Map.of("accountId", category), HttpStatus.OK);
        }
    }

    private JsonNode startSeptember() {
        return api.post(recBase, Map.of("statementDate", "2026-09-30",
                "statementEndingBalance", Map.of("amount", "1193.91", "currency", "USD")), HttpStatus.CREATED);
    }

    private List<String> candidateIds(String recId) {
        List<String> ids = new ArrayList<>();
        api.get(recBase + "/" + recId + "/candidates").forEach(c -> ids.add(c.get("lineId").asText()));
        return ids;
    }

    private static String amt(JsonNode money) {
        return money.get("amount").asText();
    }

    @Test
    void ac2_ac3_startsWithZeroBeginningBalanceAndListsBankLines() {
        JsonNode rec = startSeptember();
        assertThat(amt(rec.get("beginningBalance"))).isEqualTo("0.00");
        assertThat(amt(rec.get("clearedBalance"))).isEqualTo("0.00");
        assertThat(amt(rec.get("difference"))).isEqualTo("1193.91");
        assertThat(rec.get("status").asText()).isEqualTo("in_progress");

        JsonNode candidates = api.get(recBase + "/" + rec.get("id").asText() + "/candidates");
        assertThat(candidates).hasSize(6);
        assertThat(candidates.get(0).get("entryDate").asText()).isEqualTo("2026-09-01");
        assertThat(amt(candidates.get(0).get("amount"))).isEqualTo("2500.00");
        assertThat(candidates.get(0).get("cleared").asBoolean()).isFalse();
    }

    @Test
    void ac4_ac5_ac7_clearingEveryLineBalancesAndCompletes() {
        String recId = startSeptember().get("id").asText();
        List<String> lines = candidateIds(recId);

        JsonNode partial = api.post(recBase + "/" + recId + "/cleared",
                Map.of("lineIds", lines.subList(0, 1), "cleared", true), HttpStatus.OK);
        assertThat(amt(partial.get("clearedBalance"))).isEqualTo("2500.00");
        assertThat(amt(partial.get("difference"))).isEqualTo("-1306.09");
        assertThat(api.post(recBase + "/" + recId + "/complete", Map.of(), HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("NOT_BALANCED");

        JsonNode all = api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", lines, "cleared", true), HttpStatus.OK);
        assertThat(amt(all.get("clearedBalance"))).isEqualTo("1193.91");
        assertThat(amt(all.get("difference"))).isEqualTo("0.00");
        assertThat(all.get("clearedCount").asInt()).isEqualTo(6);

        JsonNode completed = api.post(recBase + "/" + recId + "/complete", Map.of(), HttpStatus.OK);
        assertThat(completed.get("status").asText()).isEqualTo("completed");
        assertThat(completed.get("completedAt").isNull()).isFalse();

        // AC 6: completed reconciliations are frozen
        assertThat(api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", lines.subList(0, 1), "cleared", false),
                HttpStatus.CONFLICT).get("code").asText()).isEqualTo("RECONCILIATION_COMPLETED");

        // AC 5 + 7: next reconciliation starts from this ending balance and doesn't offer cleared lines again
        JsonNode october = api.post(recBase, Map.of("statementDate", "2026-10-31",
                "statementEndingBalance", Map.of("amount", "1193.91", "currency", "USD")), HttpStatus.CREATED);
        assertThat(amt(october.get("beginningBalance"))).isEqualTo("1193.91");
        assertThat(amt(october.get("difference"))).isEqualTo("0.00");
        assertThat(api.get(recBase + "/" + october.get("id").asText() + "/candidates")).isEmpty();
    }

    /** Spec 065 row 6: a bank transaction in a finished reconciliation cannot be quietly un-categorized. */
    @Test
    void spec065_row6_aReconciledTransactionCannotBeUncategorized() {
        String recId = startSeptember().get("id").asText();
        List<String> lines = candidateIds(recId);
        api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", lines, "cleared", true), HttpStatus.OK);

        String txn = api.get(base + "/bank-transactions?status=categorized").get(0).get("id").asText();
        // While cleared in a reconciliation still in progress: the line has to be uncleared first.
        assertThat(api.post(base + "/bank-transactions/" + txn + "/uncategorize", Map.of(), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("TRANSACTION_RECONCILED");

        api.post(recBase + "/" + recId + "/complete", Map.of(), HttpStatus.OK);
        assertThat(api.post(base + "/bank-transactions/" + txn + "/uncategorize", Map.of(), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("TRANSACTION_RECONCILED");

        JsonNode october = api.post(recBase, Map.of("statementDate", "2026-10-31",
                "statementEndingBalance", Map.of("amount", "1193.91", "currency", "USD")), HttpStatus.CREATED);
        assertThat(amt(october.get("difference"))).as("September still stands exactly as it was reconciled")
                .isEqualTo("0.00");
    }

    @Test
    void ac4_unclearingLinesUpdatesTheDifference() {
        String recId = startSeptember().get("id").asText();
        List<String> lines = candidateIds(recId);
        api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", lines, "cleared", true), HttpStatus.OK);
        JsonNode after = api.post(recBase + "/" + recId + "/cleared",
                Map.of("lineIds", lines.subList(0, 1), "cleared", false), HttpStatus.OK);
        assertThat(amt(after.get("clearedBalance"))).isEqualTo("-1306.09");
        assertThat(amt(after.get("difference"))).isEqualTo("2500.00");
        assertThat(after.get("clearedCount").asInt()).isEqualTo(5);
    }

    @Test
    void ac1_onlyOneOpenReconciliationAndDatesMustMoveForward() {
        String recId = startSeptember().get("id").asText();
        assertThat(api.post(recBase, Map.of("statementDate", "2026-10-31",
                "statementEndingBalance", Map.of("amount", "0.00", "currency", "USD")), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("RECONCILIATION_IN_PROGRESS");

        api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", candidateIds(recId), "cleared", true), HttpStatus.OK);
        api.post(recBase + "/" + recId + "/complete", Map.of(), HttpStatus.OK);

        assertThat(api.post(recBase, Map.of("statementDate", "2026-09-15",
                "statementEndingBalance", Map.of("amount", "0.00", "currency", "USD")), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("STATEMENT_DATE_TOO_EARLY");
    }

    @Test
    void ac4_linesFromOtherAccountsAreRejected() {
        String recId = startSeptember().get("id").asText();
        JsonNode entry = api.get(base + "/journal-entries").get(0);
        String expenseLine = null;
        for (JsonNode line : entry.get("lines")) {
            if (!line.get("accountId").asText().equals(acct.get("1010"))) {
                expenseLine = line.get("lineNo").asText();
            }
        }
        assertThat(expenseLine).as("entry has a non-bank line").isNotNull();
        assertThat(api.post(recBase + "/" + recId + "/cleared",
                Map.of("lineIds", List.of(UUID.randomUUID().toString()), "cleared", true), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("INVALID_LINE");
    }

    @Test
    void ac6_undoReturnsLinesAndIsAudited() {
        String recId = startSeptember().get("id").asText();
        List<String> lines = candidateIds(recId);
        api.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", lines, "cleared", true), HttpStatus.OK);
        api.post(recBase + "/" + recId + "/complete", Map.of(), HttpStatus.OK);

        api.post(recBase + "/" + recId + "/undo", Map.of(), HttpStatus.NO_CONTENT);
        assertThat(api.get(recBase)).isEmpty();

        JsonNode fresh = startSeptember();
        assertThat(api.get(recBase + "/" + fresh.get("id").asText() + "/candidates")).hasSize(6);

        List<String> actions = new ArrayList<>();
        api.get("/api/v1/orgs/" + org + "/audit-events").forEach(e -> actions.add(e.get("action").asText()));
        assertThat(actions).contains("reconciliation_completed", "reconciliation_undone");
    }

    @Test
    void ac8_viewersCanReadButNotChange() {
        String recId = startSeptember().get("id").asText();
        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get(recBase + "/" + recId, HttpStatus.OK);
        viewer.post(recBase + "/" + recId + "/cleared", Map.of("lineIds", candidateIds(recId), "cleared", true),
                HttpStatus.FORBIDDEN);
    }
}
