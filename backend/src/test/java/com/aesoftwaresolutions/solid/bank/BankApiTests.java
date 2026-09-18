package com.aesoftwaresolutions.solid.bank;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Spec 008, AC 3–9. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class BankApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String base;
    Map<String, String> acct = new HashMap<>();
    String checking;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        checking = api.post(base + "/bank-accounts", Map.of("name", "Business Checking", "glAccountId", acct.get("1010"), "mask", "1234"),
                HttpStatus.CREATED).get("id").asText();
    }

    private ResponseEntity<JsonNode> upload(String bankAccountId, String filename, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(base + "/bank-accounts/" + bankAccountId + "/imports", HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
    }

    private JsonNode importFixture(String bankAccountId, String fixture) {
        ResponseEntity<JsonNode> r = upload(bankAccountId, fixture, StatementParserTests.fixture(fixture));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private JsonNode newTxns() {
        return api.get(base + "/bank-transactions?status=new");
    }

    private JsonNode txnByDescription(String contains) {
        for (JsonNode t : api.get(base + "/bank-transactions")) {
            if (t.get("description").asText().contains(contains)) {
                return t;
            }
        }
        throw new AssertionError("no txn containing " + contains);
    }

    @Test
    void bankAccountMustLinkToBankOrCardLedgerAccount() {
        JsonNode bad = api.post(base + "/bank-accounts", Map.of("name", "Nope", "glAccountId", acct.get("6010")), HttpStatus.CONFLICT);
        assertThat(bad.get("code").asText()).isEqualTo("INVALID_GL_ACCOUNT");
        api.post(base + "/bank-accounts", Map.of("name", "Card", "glAccountId", acct.get("2010")), HttpStatus.CREATED);
        api.post(base + "/bank-accounts", Map.of("name", "Dup", "glAccountId", acct.get("1010")), HttpStatus.CONFLICT);
    }

    @Test
    void ac3_reimportingSameFileCreatesNoDuplicatesButKeepsIdenticalChargesInOneFile() {
        JsonNode first = importFixture(checking, "checking-2026-09.csv");
        assertThat(first.get("format").asText()).isEqualTo("csv");
        assertThat(first.get("parsed").asInt()).isEqualTo(6);
        assertThat(first.get("imported").asInt()).isEqualTo(6); // both COFFEE SHOP rows kept
        JsonNode second = importFixture(checking, "checking-2026-09.csv");
        assertThat(second.get("imported").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(6);

        assertThat(importFixture(checking, "checking-sgml.ofx").get("imported").asInt()).isEqualTo(3);
        assertThat(importFixture(checking, "checking-sgml.ofx").get("imported").asInt()).isZero();
        assertThat(newTxns()).hasSize(9);
    }

    @Test
    void ac2_badFilesAre400WithLineNumber() {
        ResponseEntity<JsonNode> r = upload(checking, "bad.csv", "Date,Description,Amount\n2026-01-01,A,abc\n");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("detail").asText()).startsWith("Line 2:");
        assertThat(upload(checking, "empty.csv", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac5_categorizePostsBalancedEntryForDepositAndWithdrawal() {
        importFixture(checking, "checking-2026-09.csv");

        JsonNode deposit = txnByDescription("ACME CLIENT");
        JsonNode done = api.post(base + "/bank-transactions/" + deposit.get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("4010")), HttpStatus.OK);
        assertThat(done.get("status").asText()).isEqualTo("categorized");
        JsonNode entry = api.get(base + "/journal-entries/" + done.get("journalEntryId").asText());
        assertThat(entry.get("source").asText()).isEqualTo("bank");
        assertThat(entry.get("entryDate").asText()).isEqualTo("2026-09-01");
        assertThat(entry.get("lines").get(0).get("accountId").asText()).isEqualTo(acct.get("1010"));
        assertThat(entry.get("lines").get(0).get("amount").get("amount").asText()).isEqualTo("2500.00");
        assertThat(entry.get("lines").get(1).get("amount").get("amount").asText()).isEqualTo("-2500.00");

        JsonNode adobe = txnByDescription("ADOBE");
        api.post(base + "/bank-transactions/" + adobe.get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.OK);

        JsonNode pl = api.get(base + "/reports/profit-and-loss?from=2026-09-01&to=2026-09-30");
        assertThat(pl.get("netIncome").get("amount").asText()).isEqualTo("2445.01");
        JsonNode tb = api.get(base + "/reports/trial-balance?asOf=2026-09-30");
        assertThat(tb.get("totalDebit")).isEqualTo(tb.get("totalCredit"));

        JsonNode again = api.post(base + "/bank-transactions/" + adobe.get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.CONFLICT);
        assertThat(again.get("code").asText()).isEqualTo("ALREADY_CATEGORIZED");
        assertThat(api.post(base + "/bank-transactions/" + txnByDescription("STAPLES").get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("1010")), HttpStatus.CONFLICT).get("code").asText()).isEqualTo("SAME_ACCOUNT");
    }

    @Test
    void ac5_creditCardChargeDebitsExpenseAndCreditsCard() {
        String card = api.post(base + "/bank-accounts", Map.of("name", "Card", "glAccountId", acct.get("2010")), HttpStatus.CREATED)
                .get("id").asText();
        importFixture(card, "card-xml.qfx");
        JsonNode netflix = txnByDescription("NETFLIX");
        api.post(base + "/bank-transactions/" + netflix.get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.OK);

        JsonNode bs = api.get(base + "/reports/balance-sheet?asOf=2026-09-30");
        assertThat(bs.get("liabilities").get("total").get("amount").asText()).isEqualTo("19.99");
        assertThat(bs.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void ac4_rulesThenHistorySuggestions() {
        importFixture(checking, "checking-2026-09.csv");
        api.post(base + "/categorization-rules", Map.of("contains", "google", "accountId", acct.get("6010")), HttpStatus.CREATED);
        api.post(base + "/categorization-rules", Map.of("contains", "GOOGLE *ADS", "accountId", acct.get("6900"), "priority", 5),
                HttpStatus.CREATED);

        JsonNode google = txnByDescription("GOOGLE");
        assertThat(google.get("suggestionSource").asText()).isEqualTo("rule");
        assertThat(google.get("suggestedAccountId").asText()).as("priority 5 beats default 100").isEqualTo(acct.get("6900"));

        // History: categorize one Adobe charge, then import a different month's Adobe charge with a different phone number.
        api.post(base + "/bank-transactions/" + txnByDescription("ADOBE").get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.OK);
        ResponseEntity<JsonNode> r = upload(checking, "oct.csv",
                "Date,Description,Amount\n2026-10-03,ADOBE *CREATIVE CLD 800-555-0101 CA,-54.99\n");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode october = null;
        for (JsonNode t : newTxns()) {
            if (t.get("postedDate").asText().equals("2026-10-03")) {
                october = t;
            }
        }
        assertThat(october).isNotNull();
        assertThat(october.get("suggestionSource").asText()).isEqualTo("history");
        assertThat(october.get("suggestedAccountId").asText()).isEqualTo(acct.get("6220"));
        assertThat(txnByDescription("STAPLES").get("suggestedAccountId").isNull()).isTrue();
    }

    @Test
    void ac6_bulkCategorizeIsAllOrNothing() {
        importFixture(checking, "checking-2026-09.csv");
        String adobe = txnByDescription("ADOBE").get("id").asText();
        String staples = txnByDescription("STAPLES").get("id").asText();

        api.post(base + "/bank-transactions/categorize", Map.of("items", List.of(
                Map.of("id", adobe, "accountId", acct.get("6220")),
                Map.of("id", staples, "accountId", acct.get("6000")))), HttpStatus.CONFLICT); // header account fails
        assertThat(newTxns()).hasSize(6);
        assertThat(api.get(base + "/journal-entries")).isEmpty();

        JsonNode ok = api.post(base + "/bank-transactions/categorize", Map.of("items", List.of(
                Map.of("id", adobe, "accountId", acct.get("6220")),
                Map.of("id", staples, "accountId", acct.get("6110")))), HttpStatus.OK);
        assertThat(ok).hasSize(2);
        assertThat(newTxns()).hasSize(4);
    }

    @Test
    void ac7_uncategorizeReversesAndExcludeWorks() {
        importFixture(checking, "checking-2026-09.csv");
        String adobe = txnByDescription("ADOBE").get("id").asText();
        api.post(base + "/bank-transactions/" + adobe + "/categorize", Map.of("accountId", acct.get("6220")), HttpStatus.OK);

        JsonNode back = api.post(base + "/bank-transactions/" + adobe + "/uncategorize", Map.of(), HttpStatus.OK);
        assertThat(back.get("status").asText()).isEqualTo("new");
        assertThat(back.get("journalEntryId").isNull()).isTrue();
        assertThat(api.get(base + "/journal-entries?status=posted")).hasSize(2); // original + reversal
        assertThat(api.get(base + "/reports/profit-and-loss?from=2026-09-01&to=2026-09-30").get("netIncome").get("amount").asText())
                .isEqualTo("0.00");

        api.post(base + "/bank-transactions/" + adobe + "/categorize", Map.of("accountId", acct.get("6110")), HttpStatus.OK);

        String coffee = txnByDescription("COFFEE").get("id").asText();
        assertThat(api.post(base + "/bank-transactions/" + coffee + "/exclude", Map.of(), HttpStatus.OK).get("status").asText())
                .isEqualTo("excluded");
        api.post(base + "/bank-transactions/" + coffee + "/exclude", Map.of(), HttpStatus.CONFLICT);
        assertThat(api.post(base + "/bank-transactions/" + coffee + "/uncategorize", Map.of(), HttpStatus.OK).get("status").asText())
                .isEqualTo("new");
    }

    @Test
    void ac8_lockedPeriodLeavesTransactionNew() {
        importFixture(checking, "checking-2026-09.csv");
        api.put(base + "/period-lock", Map.of("lockedThrough", "2026-09-30"));
        String adobe = txnByDescription("ADOBE").get("id").asText();
        assertThat(api.post(base + "/bank-transactions/" + adobe + "/categorize", Map.of("accountId", acct.get("6220")),
                HttpStatus.CONFLICT).get("code").asText()).isEqualTo("PERIOD_LOCKED");
        assertThat(txnByDescription("ADOBE").get("status").asText()).isEqualTo("new");
    }

    @Test
    void ac9_viewerCannotImportOrCategorize() {
        importFixture(checking, "checking-2026-09.csv");
        ApiClient viewer = new ApiClient(rest);
        api.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get(base + "/bank-transactions", HttpStatus.OK);
        viewer.post(base + "/bank-transactions/" + txnByDescription("ADOBE").get("id").asText() + "/categorize",
                Map.of("accountId", acct.get("6220")), HttpStatus.FORBIDDEN);
    }
}
