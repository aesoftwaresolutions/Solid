package com.aesoftwaresolutions.solid.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spec 030, AC 2-6. A stub stands in for Ollama so the tests never need a model — and so the exact bytes Solid
 * would send to one can be inspected.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "solid.ai.enabled=true")
@Import(TestcontainersConfiguration.class)
class SuggestionApiTests {

    private static HttpServer stub;
    private static final AtomicReference<String> NEXT_RESPONSE = new AtomicReference<>();
    private static final List<String> REQUESTS = new ArrayList<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/generate", exchange -> {
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = NEXT_RESPONSE.get();
            if (body == null) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("solid.ai.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
        registry.add("solid.ai.model", () -> "test-model");
        registry.add("solid.ai.timeout-seconds", () -> 2);
    }

    /** What Ollama's /api/generate returns: the model's text sits in "response". */
    private static String modelAnswers(String answer) {
        return "{\"model\":\"test-model\",\"done\":true,\"response\":" + quote(answer) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Autowired
    TestRestTemplate rest;

    ApiClient api;
    String base;
    String txnId;
    Map<String, String> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        NEXT_RESPONSE.set(null);
        api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        base = "/api/v1/orgs/" + org + "/entities/" + entity;
        api.post(base + "/accounts/apply-template", Map.of("template", "schedule_c"), HttpStatus.CREATED)
                .forEach(a -> acct.put(a.get("code").asText(), a.get("id").asText()));
        String bankAccount = api.post(base + "/bank-accounts",
                Map.of("name", "Checking", "glAccountId", acct.get("1010")), HttpStatus.CREATED).get("id").asText();

        String csv = "Date,Description,Amount\n2026-03-01,SQ *BLUE BOTTLE COFFEE,-18.75\n";
        api.postFile(base + "/bank-accounts/" + bankAccount + "/imports", "statement.csv",
                csv.getBytes(StandardCharsets.UTF_8), HttpStatus.CREATED);
        txnId = api.get(base + "/bank-transactions?status=new").get(0).get("id").asText();
    }

    private JsonNode suggest() {
        return api.post(base + "/bank-transactions/" + txnId + "/suggest", Map.of(), HttpStatus.OK);
    }

    @Test
    void ac2_ac5_ac6_aValidCodeComesBackAsAnAccountAndNothingIsSaved() {
        NEXT_RESPONSE.set(modelAnswers("{\"code\": \"6160\"}"));

        JsonNode suggestion = suggest();
        assertThat(suggestion.get("accountId").asText()).isEqualTo(acct.get("6160"));
        assertThat(suggestion.get("code").asText()).isEqualTo("6160");
        assertThat(suggestion.get("source").asText()).isEqualTo("ai");
        assertThat(suggestion.get("model").asText()).isEqualTo("test-model");

        // AC5: what was actually sent to the model.
        assertThat(REQUESTS).hasSize(1);
        String sent = REQUESTS.get(0);
        assertThat(sent).contains("SQ *BLUE BOTTLE COFFEE").contains("6160").contains("-18.75");
        assertThat(sent).doesNotContain("Test sole_prop").doesNotContain(api.email());

        // AC6: a suggestion is not a decision.
        JsonNode txn = api.get(base + "/bank-transactions?status=new").get(0);
        assertThat(txn.get("suggestedAccountId").isNull()).isTrue();
        assertThat(txn.get("status").asText()).isEqualTo("new");
    }

    @Test
    void ac3_anythingButAValidCodeIsIgnored() {
        NEXT_RESPONSE.set(modelAnswers("{\"code\": \"9999\"}"));
        assertThat(suggest().get("reason").asText()).contains("not one of this entity's accounts");

        NEXT_RESPONSE.set(modelAnswers("{\"code\": \"unknown\"}"));
        assertThat(suggest().get("accountId").isNull()).isTrue();

        NEXT_RESPONSE.set(modelAnswers("I think this is coffee, so meals."));
        assertThat(suggest().get("accountId").isNull()).isTrue();

        NEXT_RESPONSE.set(modelAnswers(""));
        assertThat(suggest().get("accountId").isNull()).isTrue();

        // A header account is not a candidate even if the model names it.
        NEXT_RESPONSE.set(modelAnswers("{\"code\": \"6000\"}"));
        assertThat(suggest().get("accountId").isNull()).isTrue();
    }

    @Test
    void ac4_aBrokenModelIsNeverAServerError() {
        NEXT_RESPONSE.set(null); // the stub answers 500
        JsonNode suggestion = suggest();
        assertThat(suggestion.get("accountId").isNull()).isTrue();
        assertThat(suggestion.get("reason").asText()).contains("did not answer");
    }
}
