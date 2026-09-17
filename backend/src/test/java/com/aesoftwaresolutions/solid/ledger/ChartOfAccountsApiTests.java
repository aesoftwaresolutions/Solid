package com.aesoftwaresolutions.solid.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ChartOfAccountsApiTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String org;
    String entity;
    String base;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
        org = api.newOrg();
        entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity + "/accounts";
    }

    private static Map<String, Object> account(String code, String name, String type) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        return body;
    }

    @Test
    void ac1_createsAccountsAndRejectsDuplicateCodes() {
        JsonNode created = api.post(base, account("1010", "Checking", "asset"), HttpStatus.CREATED);
        assertThat(created.get("type").asText()).isEqualTo("asset");
        assertThat(created.get("isArchived").asBoolean()).isFalse();

        JsonNode dup = api.post(base, account("1010", "Other", "asset"), HttpStatus.CONFLICT);
        assertThat(dup.get("code").asText()).isEqualTo("ACCOUNT_CODE_TAKEN");

        api.post(base, account("10 10", "Bad code", "asset"), HttpStatus.BAD_REQUEST);
        api.post(base, account("1020", "Bad type", "revenue"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac2_parentRules() {
        Map<String, Object> header = account("6000", "Expenses", "expense");
        header.put("isHeader", true);
        String headerId = api.post(base, header, HttpStatus.CREATED).get("id").asText();
        String leafId = api.post(base, account("1010", "Checking", "asset"), HttpStatus.CREATED).get("id").asText();

        Map<String, Object> child = account("6010", "Advertising", "expense");
        child.put("parentId", headerId);
        assertThat(api.post(base, child, HttpStatus.CREATED).get("parentId").asText()).isEqualTo(headerId);

        Map<String, Object> wrongType = account("1020", "Savings", "asset");
        wrongType.put("parentId", headerId);
        assertThat(api.post(base, wrongType, HttpStatus.CONFLICT).get("code").asText()).isEqualTo("INVALID_PARENT");

        Map<String, Object> notHeader = account("1030", "Petty cash", "asset");
        notHeader.put("parentId", leafId);
        assertThat(api.post(base, notHeader, HttpStatus.CONFLICT).get("code").asText()).isEqualTo("INVALID_PARENT");

        Map<String, Object> missing = account("1040", "Ghost", "asset");
        missing.put("parentId", UUID.randomUUID().toString());
        api.post(base, missing, HttpStatus.CONFLICT);
    }

    @Test
    void ac3_taxLineMustExistAndMatchAccountType() {
        Map<String, Object> ads = account("6010", "Advertising", "expense");
        ads.put("taxLineCode", "F1040.SCH_C.L8");
        assertThat(api.post(base, ads, HttpStatus.CREATED).get("taxLineCode").asText()).isEqualTo("F1040.SCH_C.L8");

        Map<String, Object> unknown = account("6020", "Mystery", "expense");
        unknown.put("taxLineCode", "F1040.SCH_C.L99");
        api.post(base, unknown, HttpStatus.BAD_REQUEST);

        Map<String, Object> incomeOnExpense = account("6030", "Wrong", "expense");
        incomeOnExpense.put("taxLineCode", "F1040.SCH_C.L1");
        assertThat(api.post(base, incomeOnExpense, HttpStatus.CONFLICT).get("code").asText())
                .isEqualTo("TAX_LINE_INCOMPATIBLE");

        Map<String, Object> onAsset = account("1010", "Checking", "asset");
        onAsset.put("taxLineCode", "F1040.SCH_C.L8");
        api.post(base, onAsset, HttpStatus.CONFLICT);
    }

    @Test
    void ac4_ac5_appliesScheduleCTemplateOnce() {
        JsonNode accounts = api.post(base + "/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED);
        assertThat(accounts.size()).isGreaterThanOrEqualTo(40);

        Map<String, JsonNode> byCode = new HashMap<>();
        accounts.forEach(a -> byCode.put(a.get("code").asText(), a));
        assertThat(byCode.get("6010").get("taxLineCode").asText()).isEqualTo("F1040.SCH_C.L8");
        assertThat(byCode.get("4010").get("taxLineCode").asText()).isEqualTo("F1040.SCH_C.L1");
        assertThat(byCode.get("3020").get("taxLineCode").isNull()).isTrue(); // owner's draws: not deductible
        assertThat(byCode.get("6010").get("parentId").asText()).isEqualTo(byCode.get("6000").get("id").asText());

        accounts.forEach(a -> {
            boolean expenseLeaf = a.get("type").asText().equals("expense") && !a.get("isHeader").asBoolean();
            if (expenseLeaf) {
                assertThat(a.get("taxLineCode").isNull()).as(a.get("code") + " needs a tax line").isFalse();
            }
        });

        JsonNode again = api.post(base + "/apply-template", Map.of("template", "schedule_c"), HttpStatus.CONFLICT);
        assertThat(again.get("code").asText()).isEqualTo("COA_NOT_EMPTY");
    }

    @Test
    void ac4_templateOnlyForApplicableEntityKinds() {
        String scorp = api.newEntity(org, "s_corp");
        JsonNode problem = api.post("/api/v1/orgs/" + org + "/entities/" + scorp + "/accounts/apply-template",
                Map.of("template", "schedule_c"), HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("TEMPLATE_NOT_APPLICABLE");
        api.post(base + "/apply-template", Map.of("template", "../../etc/passwd"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac6_cannotArchiveHeaderWithActiveChildren() {
        JsonNode accounts = api.post(base + "/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED);
        Map<String, String> ids = new HashMap<>();
        accounts.forEach(a -> ids.put(a.get("code").asText(), a.get("id").asText()));

        JsonNode problem = api.patch(base + "/" + ids.get("6000"), Map.of("archived", true), HttpStatus.CONFLICT);
        assertThat(problem.get("code").asText()).isEqualTo("HAS_ACTIVE_CHILDREN");

        JsonNode archived = api.patch(base + "/" + ids.get("6900"), Map.of("archived", true), HttpStatus.OK);
        assertThat(archived.get("isArchived").asBoolean()).isTrue();

        JsonNode renamed = api.patch(base + "/" + ids.get("6010"), Map.of("name", "Marketing", "taxLineCode", ""),
                HttpStatus.OK);
        assertThat(renamed.get("name").asText()).isEqualTo("Marketing");
        assertThat(renamed.get("taxLineCode").isNull()).isTrue();
    }

    @Test
    void ac7_otherOrgsEntityIs404() {
        String otherOrg = api.newOrg();
        api.get("/api/v1/orgs/" + otherOrg + "/entities/" + entity + "/accounts", HttpStatus.NOT_FOUND);
    }

    @Test
    void taxLineCatalogEndpoint() {
        JsonNode lines = api.get("/api/v1/tax-lines?form=F1040.SCH_C");
        assertThat(lines.size()).isGreaterThan(20);
        assertThat(lines.get(0).get("code").asText()).startsWith("F1040.SCH_C.");
    }
}
