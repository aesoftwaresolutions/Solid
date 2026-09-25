package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 065 — rows 8 and 14 of the fourth review. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FourthReviewIamTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient db;

    @Test
    void row8_aViewerCannotListEveryMembersEmail() {
        ApiClient owner = new ApiClient(rest);
        ApiClient viewer = new ApiClient(rest);
        String org = owner.newOrg();
        owner.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"),
                HttpStatus.CREATED);

        viewer.get("/api/v1/orgs/" + org + "/members", HttpStatus.FORBIDDEN);
        viewer.get("/api/v1/orgs/" + org + "/entities", HttpStatus.OK);
        assertThat(owner.get("/api/v1/orgs/" + org + "/members")).hasSize(2);
    }

    @Test
    void row14_theAuditLogRecordsTheNearestUntrustedAddressNotWhateverTheCallerClaims() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // A caller claiming to be 203.0.113.9, arriving through a proxy that reports 198.51.100.7. The proxy's
        // own report is the one worth believing; the caller's claim is just a string they typed.
        headers.add("X-Forwarded-For", "203.0.113.9, 198.51.100.7");
        String email = "nobody-" + System.nanoTime() + "@example.test";
        rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "not-the-password"), headers), JsonNode.class);

        String recorded = db.sql("""
                select actor_ip from audit.event where action = 'login_failed'
                order by seq desc limit 1""").query(String.class).single();
        assertThat(recorded).isEqualTo("198.51.100.7");
    }
}
