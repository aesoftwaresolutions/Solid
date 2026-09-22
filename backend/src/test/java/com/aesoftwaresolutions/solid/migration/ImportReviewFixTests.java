package com.aesoftwaresolutions.solid.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 043: the importer must never say "ready" about a file the books would then refuse. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportReviewFixTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
    }

    private JsonNode preview(String kind, String csv) {
        return api.post(base + "/imports/" + kind + "/preview", Map.of("csv", csv), HttpStatus.OK);
    }

    private static String detail(JsonNode result, int index) {
        return result.get("rows").get(index).get("detail").asText();
    }

    @Test
    void aTaxLineTheLedgerWouldRefuseIsReportedByThePreview() {
        JsonNode plan = preview("accounts", """
                code,name,type,header,tax line
                4000,Sales,income,yes,F1040.SCH_C.L1
                6000,Rent,expense,no,F1040.SCH_C.L1
                """);

        assertThat(plan.get("ready").asBoolean()).isFalse();
        assertThat(detail(plan, 0)).contains("header account cannot carry a tax line");
        assertThat(detail(plan, 1)).contains("F1040.SCH_C.L1");
    }

    @Test
    void shapesTheDatabaseWouldRejectAreReportedTooRatherThanBlowingUpOnCommit() {
        JsonNode plan = preview("accounts", """
                code,name,type,subtype
                this-code-is-far-too-long-to-store,Bad,asset,
                1010,Checking,asset,Bank Account
                """);

        assertThat(plan.get("problemCount").asInt()).isEqualTo(2);
        assertThat(detail(plan, 0)).contains("1-20 characters");
        assertThat(detail(plan, 1)).contains("lower-case letters");
    }

    @Test
    void anArchivedParentIsAProblemBeforeAnythingIsWritten() {
        String headerId = api.post(base + "/accounts",
                Map.of("code", "1000", "name", "Assets", "type", "asset", "isHeader", true), HttpStatus.CREATED)
                .get("id").asText();
        api.patch(base + "/accounts/" + headerId, Map.of("archived", true), HttpStatus.OK);

        JsonNode plan = preview("accounts", """
                code,name,type,parent
                1010,Checking,asset,1000
                """);

        assertThat(plan.get("ready").asBoolean()).isFalse();
        assertThat(detail(plan, 0)).contains("archived");
    }

    @Test
    void accountCodesAreMatchedTheWayTheDatabaseMatchesThem() {
        api.post(base + "/accounts", Map.of("code", "CASH", "name", "Cash", "type", "asset"), HttpStatus.CREATED);

        JsonNode plan = preview("accounts", """
                code,name,type
                cash,Petty cash,asset
                CASH,Cash,asset
                """);

        // 'cash' is a different code to 'CASH', so it is a new account, not a silent skip.
        assertThat(plan.get("rows").get(0).get("action").asText()).isEqualTo("create");
        assertThat(plan.get("rows").get(1).get("action").asText()).isEqualTo("skip");
    }

    @Test
    void reRunningAVendorFileStaysANoOpEvenAfterItsAccountIsArchived() {
        String accountId = api.post(base + "/accounts",
                Map.of("code", "6220", "name", "Software", "type", "expense"), HttpStatus.CREATED)
                .get("id").asText();
        String csv = """
                name,default expense account
                Jane the Contractor,6220
                """;
        api.post(base + "/imports/vendors", Map.of("csv", csv), HttpStatus.OK);
        api.patch(base + "/accounts/" + accountId, Map.of("archived", true), HttpStatus.OK);

        JsonNode again = api.post(base + "/imports/vendors", Map.of("csv", csv), HttpStatus.OK);

        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("rows").get(0).get("action").asText()).isEqualTo("skip");
    }

    @Test
    void aNewlineInsideAQuotedFieldDoesNotMoveTheLineNumbers() {
        JsonNode plan = preview("customers", """
                name,billing address
                "Acme, Inc.","1 Main St
                Springfield"
                ,nothing here
                """);

        assertThat(plan.get("problemCount").asInt()).isEqualTo(1);
        // The nameless row physically starts on line 4, not line 3.
        assertThat(plan.get("rows").get(1).get("line").asInt()).isEqualTo(4);
    }

    @Test
    void aFileTooBigToBeSensibleIsRefusedBeforeItIsParsed() {
        String csv = "name\n" + "Customer with a reasonably long name here\n".repeat(120_000);

        JsonNode problem = api.post(base + "/imports/customers/preview", Map.of("csv", csv), HttpStatus.CONFLICT);

        assertThat(problem.toString()).contains("IMPORT_TOO_LARGE");
    }
}
