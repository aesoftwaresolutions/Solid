package com.aesoftwaresolutions.solid.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 065 — rows 9, 13, 15, 16 and 18 of the fourth review. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FourthReviewTests {

    @Autowired
    TestRestTemplate rest;

    /** The application's own pool: every connection in it has become solid_app. */
    @Autowired
    JdbcClient db;

    ApiClient api;
    String org;
    String base;
    Map<String, String> acct = new HashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "USD");
    }

    private String entry(String date, String debitAccount, String creditAccount, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("entryDate", date);
        body.put("post", true);
        body.put("lines", List.of(Map.of("accountId", debitAccount, "amount", money(amount)),
                Map.of("accountId", creditAccount, "amount", money("-" + amount))));
        return api.post(base + "/journal-entries", body, HttpStatus.CREATED).get("id").asText();
    }

    // ---------------- row 9 ----------------

    @Test
    void row9_anAccountNameCannotBecomeASpreadsheetFormulaInTheTaxLineCsv() {
        String sneaky = api.post(base + "/accounts", Map.of("code", "6999",
                "name", "=HYPERLINK(\"http://example.test/x\";\"open\")", "type", "expense"), HttpStatus.CREATED)
                .get("id").asText();
        entry("2026-02-01", sneaky, acct.get("1010"), "10.00");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(api.token());
        String csv = rest.exchange(base + "/reports/tax-lines.csv?taxYear=2026", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();

        assertThat(csv).contains("'=HYPERLINK");
        assertThat(csv).doesNotContain(",=HYPERLINK").doesNotContain(",\"=HYPERLINK");
        assertThat(csv).as("a real negative amount stays a number").doesNotContain("'-");
    }

    // ---------------- row 13 ----------------

    @Test
    void row13_twoRecurringRunsAtOnceBothFinishAndPostEachMonthOnce() throws Exception {
        Map<String, Object> template = new HashMap<>();
        template.put("name", "Rent");
        template.put("frequency", "monthly");
        template.put("startDate", "2026-01-15");
        template.put("dayOfMonth", 15);
        template.put("lines", List.of(Map.of("accountId", acct.get("6010"), "amount", money("1200.00")),
                Map.of("accountId", acct.get("1010"), "amount", money("-1200.00"))));
        api.post(base + "/recurring-entries", template, HttpStatus.CREATED);

        List<Integer> codes = together(() -> api.attempt(HttpMethod.POST, base + "/recurring-entries/run",
                Map.of("through", "2026-06-30")));

        assertThat(codes).as("neither run is aborted by the other").containsOnly(200);
        long posted = 0;
        for (JsonNode e : api.get(base + "/journal-entries")) {
            if (e.get("source").asText().equals("recurring")) {
                posted++;
            }
        }
        assertThat(posted).isEqualTo(6);
    }

    @Test
    void row13_twoReversalsAtOnceGiveOneReversalAndAClearAnswer() throws Exception {
        String id = entry("2026-03-01", acct.get("6010"), acct.get("1010"), "50.00");

        List<Integer> codes = together(() -> api.attempt(HttpMethod.POST,
                base + "/journal-entries/" + id + "/reverse", Map.of()));

        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        assertThat(api.post(base + "/journal-entries/" + id + "/reverse", Map.of(), HttpStatus.CONFLICT)
                .get("code").asText()).isEqualTo("ALREADY_REVERSED");
    }

    // ---------------- row 15 ----------------

    @Test
    void row15_theAppCannotRewriteTheInstanceKeyFingerprint() {
        assertThatThrownBy(() -> db.sql("update sys.instance set master_key_fingerprint = repeat('0', 64)").update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> db.sql("delete from sys.instance").update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void row15_theDatabaseRefusesACreditNoteLinePointingAtAnotherOrganizationsInvoiceLine() {
        List<Map<String, Object>> fks = db.sql("""
                select conname, pg_get_constraintdef(oid) as def from pg_constraint
                where conrelid in ('ar_ap.credit_note_line'::regclass, 'ar_ap.recurring_invoice_line'::regclass)
                  and contype = 'f'""").query().listOfRows();
        List<String> definitions = fks.stream().map(r -> (String) r.get("def")).toList();

        assertThat(definitions).as("an invoice line is referenced within the organization, not across it")
                .anyMatch(d -> d.contains("(org_id, invoice_line_id)") && d.contains("ar_ap.invoice_line(org_id, id)"));
        assertThat(definitions).as("a tax rate on a credit note line is a real rate of the same organization")
                .anyMatch(d -> d.contains("(org_id, tax_rate_id)") && d.contains("stx.tax_rate(org_id, id)"));
        assertThat(definitions.stream().filter(d -> d.contains("stx.tax_rate(org_id, id)")).count())
                .as("and the same for recurring invoice lines").isEqualTo(2);
    }

    @Test
    void row15_aRecurringLineOfZeroIsRefusedByTheDatabase() {
        String def = db.sql("""
                select string_agg(pg_get_constraintdef(oid), ' ') from pg_constraint
                where conrelid = 'gl.recurring_line'::regclass and contype = 'c'""").query(String.class).single();
        assertThat(def).contains("amount_minor <> 0");
    }

    // ---------------- row 16 ----------------

    @Test
    void row16_aLogoWithAbsurdDimensionsIsRefusedBeforeItIsEverDecoded() throws IOException {
        // Blank pixels compress to almost nothing, which is the whole trick: a few kilobytes on disk, hundreds
        // of megabytes once decoded — on every invoice.
        BufferedImage huge = new BufferedImage(6000, 6000, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", out);
        assertThat(out.size()).as("small enough to pass the byte limit").isLessThan(1_000_000);

        JsonNode refused = api.putFile(base + "/branding/logo", "big.png", out.toByteArray(),
                HttpStatus.BAD_REQUEST);
        assertThat(refused.get("code").asText()).isEqualTo("LOGO_TOO_LARGE");
    }

    // ---------------- row 18 ----------------

    @Test
    void row18_twoDepreciationRunsAtOnceBothFinish() throws Exception {
        Map<String, Object> asset = new HashMap<>();
        asset.put("name", "Laptop");
        asset.put("placedInServiceDate", "2026-01-15");
        asset.put("cost", money("3600.00"));
        asset.put("usefulLifeMonths", 36);
        asset.put("assetAccountId", acct.get("1500"));
        asset.put("accumulatedAccountId", acct.get("1510"));
        asset.put("depreciationExpenseAccountId", acct.get("6050"));
        api.post(base + "/assets", asset, HttpStatus.CREATED);

        List<Integer> codes = together(() -> api.attempt(HttpMethod.POST, base + "/depreciation-runs",
                Map.of("throughMonth", "2026-12")));

        assertThat(codes).containsOnly(201);
        long posted = 0;
        for (JsonNode e : api.get(base + "/journal-entries")) {
            if (e.get("source").asText().equals("depreciation")) {
                posted++;
            }
        }
        assertThat(posted).as("each month once").isEqualTo(12);
    }

    private List<Integer> together(Callable<Integer> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> f : futures) {
            results.add(f.get());
        }
        return results;
    }

}
