package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
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

/** Spec 029. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentTests {

    @Autowired
    TestRestTemplate rest;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(rest);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void ac1_ac2_ac3_ac6_theDocumentDescribesTheRealApi() {
        JsonNode document = api.get("/v3/api-docs");

        assertThat(document.get("openapi").asText()).startsWith("3.");
        assertThat(document.get("info").get("title").asText()).isEqualTo("Solid");
        assertThat(document.get("info").get("description").asText()).contains("Money is always an object");

        assertThat(document.get("paths").has("/api/v1/orgs/{orgId}/entities/{entityId}/journal-entries")).isTrue();
        assertThat(document.get("paths").has("/api/v1/auth/login")).isTrue();

        JsonNode money = document.get("components").get("schemas").get("Money");
        assertThat(money.get("properties").get("amount").get("type").asText())
                .as("a float would lose cents").isEqualTo("string");

        JsonNode schemes = document.get("components").get("securitySchemes");
        assertThat(schemes.has("bearer")).isTrue();
        assertThat(schemes.get("session").get("name").asText()).isEqualTo("solid_session");
    }

    @Test
    void ac4_ac5_theDocumentationNeedsASignedInUser() {
        assertThat(get("/v3/api-docs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/swagger-ui/index.html", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(get("/swagger-ui/index.html", api.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
