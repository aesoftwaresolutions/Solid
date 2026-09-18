package com.aesoftwaresolutions.solid.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * Spec 030, AC 1: with the default configuration there is no model and no call. The base URL points at a port
 * nothing listens on, so a request would fail loudly rather than pass by luck.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "solid.ai.base-url=http://127.0.0.1:1")
@Import(TestcontainersConfiguration.class)
class SuggestionDisabledTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    LocalModel model;

    @Test
    void ac1_disabledByDefaultAndNothingIsSent() {
        assertThat(model.enabled()).as("AI is opt-in").isFalse();
        assertThat(model.askForJson("anything")).isEmpty();

        ApiClient api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        String base = "/api/v1/orgs/" + org + "/entities/" + entity;
        Map<String, String> acct = new HashMap<>();
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        String bankAccount = api.post(base + "/bank-accounts",
                Map.of("name", "Checking", "glAccountId", acct.get("1010")), HttpStatus.CREATED).get("id").asText();
        api.postFile(base + "/bank-accounts/" + bankAccount + "/imports", "statement.csv",
                "Date,Description,Amount\n2026-03-01,GODADDY.COM,-19.99\n".getBytes(StandardCharsets.UTF_8),
                HttpStatus.CREATED);
        String txnId = api.get(base + "/bank-transactions?status=new").get(0).get("id").asText();

        JsonNode suggestion = api.post(base + "/bank-transactions/" + txnId + "/suggest", Map.of(), HttpStatus.OK);
        assertThat(suggestion.get("accountId").isNull()).isTrue();
        assertThat(suggestion.get("reason").asText()).contains("turned off");
    }
}
