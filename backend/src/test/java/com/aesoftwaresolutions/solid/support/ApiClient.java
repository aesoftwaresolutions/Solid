package com.aesoftwaresolutions.solid.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Small helper for API tests: send JSON, assert the status, return the body as a JSON tree. */
public class ApiClient {

    private final TestRestTemplate http;

    public ApiClient(TestRestTemplate http) {
        this.http = http;
        // JDK client supports PATCH.
        this.http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    public JsonNode post(String path, Object body, HttpStatus expected) {
        return exchange(HttpMethod.POST, path, body, expected);
    }

    public JsonNode patch(String path, Object body, HttpStatus expected) {
        return exchange(HttpMethod.PATCH, path, body, expected);
    }

    public JsonNode put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body, HttpStatus.OK);
    }

    public JsonNode get(String path, HttpStatus expected) {
        return exchange(HttpMethod.GET, path, null, expected);
    }

    public JsonNode get(String path) {
        return get(path, HttpStatus.OK);
    }

    public String newOrg() {
        return post("/api/v1/orgs", Map.of("name", "Test Org", "kind", "business"), HttpStatus.CREATED)
                .get("id").asText();
    }

    public String newEntity(String orgId, String kind) {
        return post("/api/v1/orgs/" + orgId + "/entities", Map.of("kind", kind, "legalName", "Test " + kind),
                HttpStatus.CREATED).get("id").asText();
    }

    private JsonNode exchange(HttpMethod method, String path, Object body, HttpStatus expected) {
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body), JsonNode.class);
        assertThat(response.getStatusCode()).as(method + " " + path + " -> " + response.getBody()).isEqualTo(expected);
        return response.getBody();
    }
}
