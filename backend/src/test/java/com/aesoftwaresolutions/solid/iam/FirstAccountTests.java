package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Spec 061. A copy of Solid nobody has claimed yet.
 *
 * <p>Runs against a database of its own rather than the shared one, because the whole question is what an
 * instance with no accounts at all says — and every other test class leaves accounts behind.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        // Closed signup is what a real install ships with; the test profile opens it.
        properties = "solid.auth.open-signup=false")
@Testcontainers
class FirstAccountTests {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> EMPTY_DB =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EMPTY_DB::getJdbcUrl);
        registry.add("spring.datasource.username", EMPTY_DB::getUsername);
        registry.add("spring.datasource.password", EMPTY_DB::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void ac1_ac5_anUnclaimedInstanceSaysSo_andStopsSayingItOnceItIsClaimed() {
        // AC1: readable with no account and no session — the person who needs it has neither.
        JsonNode before = rest.getForObject("/api/v1/auth/setup-state", JsonNode.class);
        assertThat(before.get("setupNeeded").asBoolean()).isTrue();
        assertThat(before.size()).as("it answers one question and volunteers nothing else").isEqualTo(1);

        // Signing up by hand, not through ApiClient: that helper claims an instance in its constructor,
        // which is the very thing under test here.
        assertThat(rest.postForEntity("/api/v1/auth/signup", Map.of(
                "email", "owner@example.test",
                "password", "correct horse battery staple",
                "displayName", "The Owner"), JsonNode.class).getStatusCode())
                .as("the first account is allowed even on a closed instance - somebody has to be first")
                .isEqualTo(HttpStatus.CREATED);

        // AC5: claimed, so the first screen stops offering to claim it.
        assertThat(rest.getForObject("/api/v1/auth/setup-state", JsonNode.class)
                .get("setupNeeded").asBoolean()).isFalse();

        // And the server, not the browser, is what actually refuses a second one.
        assertThat(rest.postForEntity("/api/v1/auth/signup", Map.of(
                "email", "someone.else@example.test",
                "password", "another long enough password",
                "displayName", "Someone Else"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
