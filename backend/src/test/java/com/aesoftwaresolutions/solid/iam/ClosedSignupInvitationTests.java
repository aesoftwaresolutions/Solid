package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * Spec 046, AC 1 — on an instance configured the way a real one is (sign-up closed once an account exists),
 * an invitation is the only way in. The rest of the suite runs with sign-up open so that it can make users
 * freely; here the first account is made through the service, the way an operator's first run does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "solid.auth.open-signup=false")
@Import(TestcontainersConfiguration.class)
class ClosedSignupInvitationTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IamService iam;

    @Test
    void signUpIsClosedButAnInvitationStillWorks() {
        String ownerEmail = "closed-owner-" + UUID.randomUUID() + "@example.test";
        iam.createInvitedUser(ownerEmail, ApiClient.PASSWORD, "Closed Instance Owner", "127.0.0.1");
        ApiClient owner = new ApiClient(rest, false).loginExisting(ownerEmail, ApiClient.PASSWORD);
        String org = owner.newOrg();

        // A stranger cannot sign up: this is the state a real instance is in.
        ApiClient stranger = new ApiClient(rest, false);
        stranger.post("/api/v1/auth/signup",
                Map.of("email", "stranger-" + UUID.randomUUID() + "@example.test",
                        "password", ApiClient.PASSWORD, "displayName", "Stranger"),
                HttpStatus.FORBIDDEN);

        // With an invitation, that same person gets an account and a membership.
        String email = "invited-" + UUID.randomUUID() + "@example.test";
        String token = owner.post("/api/v1/orgs/" + org + "/invitations",
                Map.of("email", email, "role", "accountant"), HttpStatus.CREATED).get("token").asText();
        JsonNode accepted = stranger.post("/api/v1/auth/accept-invitation",
                Map.of("token", token, "displayName", "Invited Accountant", "password", ApiClient.PASSWORD),
                HttpStatus.OK);

        assertThat(accepted.get("created").asBoolean()).isTrue();
        assertThat(owner.get("/api/v1/orgs/" + org + "/members").toString()).contains(email);
    }
}
