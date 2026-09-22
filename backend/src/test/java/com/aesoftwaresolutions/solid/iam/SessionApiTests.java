package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 051. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SessionApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient here;

    @BeforeEach
    void setUp() {
        here = new ApiClient(rest);
    }

    private ApiClient anotherSession() {
        return new ApiClient(rest, false).loginExisting(here.email(), ApiClient.PASSWORD, here.mfaSecret());
    }

    private static JsonNode currentOf(JsonNode sessions) {
        return java.util.stream.StreamSupport.stream(sessions.spliterator(), false)
                .filter(s -> s.get("current").asBoolean()).findFirst().orElseThrow();
    }

    @Test
    void ac1_ac2_youSeeYourSessionsAndCanEndTheOtherOne() {
        ApiClient elsewhere = anotherSession();

        JsonNode sessions = here.get("/api/v1/auth/sessions");
        assertThat(sessions).hasSize(2);
        JsonNode current = currentOf(sessions);
        assertThat(current.get("ip").isNull()).as("the address it was signed in from").isFalse();
        assertThat(current.get("mfaVerified").asBoolean()).isTrue();

        String otherId = java.util.stream.StreamSupport.stream(sessions.spliterator(), false)
                .filter(s -> !s.get("current").asBoolean()).findFirst().orElseThrow().get("id").asText();
        here.delete("/api/v1/auth/sessions/" + otherId, HttpStatus.NO_CONTENT);

        elsewhere.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
        here.get("/api/v1/auth/me", HttpStatus.OK);
        assertThat(here.get("/api/v1/auth/sessions")).hasSize(1);
    }

    @Test
    void ac3_someoneElsesSessionIsNotEvenVisible() {
        ApiClient stranger = new ApiClient(rest);
        String strangerSession = stranger.get("/api/v1/auth/sessions").get(0).get("id").asText();

        assertThat(here.get("/api/v1/auth/sessions").toString()).doesNotContain(strangerSession);
        here.delete("/api/v1/auth/sessions/" + strangerSession, HttpStatus.NOT_FOUND);
        here.delete("/api/v1/auth/sessions/" + UUID.randomUUID(), HttpStatus.NOT_FOUND);

        stranger.get("/api/v1/auth/me", HttpStatus.OK);
    }

    @Test
    void ac4_signingOutEverywhereElseLeavesThisOneAlone() {
        ApiClient first = anotherSession();
        ApiClient second = anotherSession();

        JsonNode result = here.post("/api/v1/auth/sessions/revoke-others", Map.of(), HttpStatus.OK);

        assertThat(result.get("revoked").asInt()).isEqualTo(2);
        first.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
        second.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
        here.get("/api/v1/auth/me", HttpStatus.OK);
        assertThat(here.get("/api/v1/auth/sessions")).hasSize(1);
    }

    @Test
    void ac5_endingTheSessionYouAreUsingSignsYouOut() {
        String mine = currentOf(here.get("/api/v1/auth/sessions")).get("id").asText();

        here.delete("/api/v1/auth/sessions/" + mine, HttpStatus.NO_CONTENT);

        here.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac6_deadSessionsAreNotOfferedAsSomethingToEnd() {
        ApiClient elsewhere = anotherSession();
        String otherId = java.util.stream.StreamSupport
                .stream(here.get("/api/v1/auth/sessions").spliterator(), false)
                .filter(s -> !s.get("current").asBoolean()).findFirst().orElseThrow().get("id").asText();

        jdbc.update("update iam.session set expires_at = now() - interval '1 hour' where id = ?::uuid", otherId);

        assertThat(here.get("/api/v1/auth/sessions")).hasSize(1);
        here.delete("/api/v1/auth/sessions/" + otherId, HttpStatus.NO_CONTENT);   // still revocable, just not listed
        elsewhere.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
    }
}
