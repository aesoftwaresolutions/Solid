package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

/**
 * Spec 065 row 7: sign-in attempts from one address are limited, so nobody can make the server hash passwords
 * as fast as they can send requests. Its own context, with a low limit; every other test runs with a high one.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "solid.auth.attempts-per-minute=5")
@Import(TestcontainersConfiguration.class)
class LoginAttemptLimitTests {

    @Autowired
    TestRestTemplate rest;

    private int login() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/auth/login",
                Map.of("email", "nobody@example.test", "password", "wrong-password"), JsonNode.class);
        return response.getStatusCode().value();
    }

    @Test
    void row7_theSixthAttemptInAMinuteIsTurnedAway() {
        for (int i = 0; i < 5; i++) {
            assertThat(login()).as("attempt " + (i + 1)).isEqualTo(401);
        }
        assertThat(login()).as("too many, too fast").isEqualTo(429);

        // The limit covers every endpoint that checks a secret, not just the password form.
        ResponseEntity<JsonNode> reset = rest.postForEntity("/api/v1/auth/reset-password",
                Map.of("token", "made-up", "newPassword", "Another-long-passphrase-1"), JsonNode.class);
        assertThat(reset.getStatusCode().value()).isEqualTo(429);
    }
}
