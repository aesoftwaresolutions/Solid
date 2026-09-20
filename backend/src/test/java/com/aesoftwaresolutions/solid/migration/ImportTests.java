package com.aesoftwaresolutions.solid.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/** Spec 040. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportTests {

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

    private JsonNode commit(String kind, String csv, HttpStatus expected) {
        return api.post(base + "/imports/" + kind, Map.of("csv", csv), expected);
    }

    private static List<String> actions(JsonNode result) {
        return java.util.stream.StreamSupport.stream(result.get("rows").spliterator(), false)
                .map(r -> r.get("action").asText()).toList();
    }

    private static final String ACCOUNTS_CSV = """
            Code,Name,Type,Subtype,Parent,Header,Tax Line
            1000,Assets,asset,,,yes,
            1010,Business checking,asset,bank,1000,no,
            4000,Sales,income,,,no,F1040.SCH_C.L1
            """;

    @Test
    void ac1_ac2_aGoodChartOfAccountsComesIn() {
        JsonNode plan = preview("accounts", ACCOUNTS_CSV);
        assertThat(plan.get("ready").asBoolean()).isTrue();
        assertThat(plan.get("created").asInt()).isEqualTo(3);
        assertThat(plan.get("committed").asBoolean()).isFalse();

        // AC7: a preview writes nothing.
        assertThat(api.get(base + "/accounts")).isEmpty();

        JsonNode done = commit("accounts", ACCOUNTS_CSV, HttpStatus.OK);
        assertThat(done.get("committed").asBoolean()).isTrue();
        assertThat(done.get("created").asInt()).isEqualTo(3);

        JsonNode accounts = api.get(base + "/accounts");
        assertThat(accounts).hasSize(3);
        JsonNode checking = byCode(accounts, "1010");
        assertThat(checking.get("name").asText()).isEqualTo("Business checking");
        assertThat(checking.get("subtype").asText()).isEqualTo("bank");
        // AC2: the parent named earlier in the same file was linked.
        assertThat(checking.get("parentId").asText()).isEqualTo(byCode(accounts, "1000").get("id").asText());
        assertThat(byCode(accounts, "4000").get("taxLineCode").asText()).isEqualTo("F1040.SCH_C.L1");
    }

    @Test
    void ac2_aParentThatIsNotAHeaderIsAProblem() {
        String csv = """
                code,name,type,header,parent
                1010,Business checking,asset,no,
                1011,Petty cash,asset,no,1010
                """;
        JsonNode plan = preview("accounts", csv);
        assertThat(plan.get("ready").asBoolean()).isFalse();
        JsonNode bad = plan.get("rows").get(1);
        assertThat(bad.get("line").asInt()).isEqualTo(3);
        assertThat(bad.get("action").asText()).isEqualTo("error");
        assertThat(bad.get("detail").asText()).contains("must be a header account");
    }

    @Test
    void ac3_runningTheSameFileTwiceAddsNothing() {
        commit("accounts", ACCOUNTS_CSV, HttpStatus.OK);

        JsonNode again = commit("accounts", ACCOUNTS_CSV, HttpStatus.OK);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(3);
        assertThat(actions(again)).containsOnly("skip");
        assertThat(api.get(base + "/accounts")).hasSize(3);
    }

    @Test
    void ac4_oneBadRowStopsTheWholeFile() {
        String csv = """
                code,name,type
                5000,Advertising,expense
                5010,Meals,exponse
                5020,Rent,expense
                """;
        JsonNode refused = commit("accounts", csv, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.get("committed").asBoolean()).isFalse();
        assertThat(refused.get("problemCount").asInt()).isEqualTo(1);
        JsonNode bad = refused.get("rows").get(1);
        assertThat(bad.get("line").asInt()).isEqualTo(3);
        assertThat(bad.get("detail").asText()).contains("exponse");

        // The two good rows were not created either: all or nothing.
        assertThat(api.get(base + "/accounts")).isEmpty();
    }

    @Test
    void ac5_customersAndVendorsComeIn() {
        commit("accounts", ACCOUNTS_CSV, HttpStatus.OK);
        api.post(base + "/accounts", Map.of("code", "6220", "name", "Software", "type", "expense"),
                HttpStatus.CREATED);

        JsonNode customers = commit("customers", """
                Name,Email,Phone,Billing Address,Notes,Colour
                "Acme, Inc.",ap@acme.test,555-0100,"1 Main St
                Springfield",Net 30,blue
                Bob's Diner,,,,,
                """, HttpStatus.OK);
        assertThat(customers.get("created").asInt()).isEqualTo(2);
        // An unused column is reported rather than silently dropped.
        assertThat(customers.get("ignoredColumns").toString()).contains("Colour");
        JsonNode saved = api.get(base + "/customers");
        assertThat(saved).hasSize(2);
        JsonNode acme = named(saved, "Acme, Inc.");
        assertThat(acme.get("email").asText()).isEqualTo("ap@acme.test");
        assertThat(acme.get("billingAddress").asText()).contains("\n");

        JsonNode vendors = commit("vendors", """
                name,email,1099,tax classification,default expense account
                Jane the Contractor,jane@example.test,yes,individual,6220
                Utility Co,,no,,
                """, HttpStatus.OK);
        assertThat(vendors.get("created").asInt()).isEqualTo(2);
        JsonNode jane = named(api.get(base + "/vendors"), "Jane the Contractor");
        assertThat(jane.get("is1099Vendor").asBoolean()).isTrue();
        assertThat(jane.get("defaultExpenseAccountId").isNull()).isFalse();
    }

    @Test
    void ac5_aVendorPointedAtAnAccountThatIsNotThereIsAProblem() {
        JsonNode plan = preview("vendors", """
                name,default expense account
                Jane the Contractor,9999
                """);
        assertThat(plan.get("rows").get(0).get("detail").asText()).contains("no account with code 9999");
    }

    @Test
    void aNameRepeatedInTheFileIsAProblemRatherThanAGuess() {
        JsonNode plan = preview("customers", """
                name
                Acme
                acme
                """);
        assertThat(plan.get("problemCount").asInt()).isEqualTo(1);
        assertThat(plan.get("rows").get(1).get("detail").asText()).contains("line 2");
    }

    @Test
    void ac6_anEnormousFileIsTurnedAway() {
        String csv = "name\n" + IntStream.range(0, 2001).mapToObj(i -> "Customer " + i + "\n")
                .reduce("", String::concat);
        JsonNode problem = api.post(base + "/imports/customers/preview", Map.of("csv", csv), HttpStatus.CONFLICT);
        assertThat(problem.toString()).contains("IMPORT_TOO_MANY_ROWS");
    }

    @Test
    void aFileMissingARequiredColumnSaysWhichOne() {
        JsonNode problem = api.post(base + "/imports/accounts/preview",
                Map.of("csv", "code,name\n1010,Checking\n"), HttpStatus.CONFLICT);
        assertThat(problem.toString()).contains("type");
    }

    @Test
    void ac8_anotherOrganizationCannotImportIntoTheseBooks() {
        new ApiClient(rest).post(base + "/imports/customers/preview", Map.of("csv", "name\nAcme\n"),
                HttpStatus.NOT_FOUND);
    }

    private static JsonNode byCode(JsonNode accounts, String code) {
        return java.util.stream.StreamSupport.stream(accounts.spliterator(), false)
                .filter(a -> a.get("code").asText().equals(code)).findFirst().orElseThrow();
    }

    private static JsonNode named(JsonNode list, String name) {
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(a -> a.get("name").asText().equals(name)).findFirst().orElseThrow();
    }
}
