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

/** Spec 046. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class InvitationApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient owner;
    String org;

    @BeforeEach
    void setUp() {
        owner = new ApiClient(rest);
        org = owner.newOrg();
    }

    private JsonNode invite(ApiClient as, String email, String role, HttpStatus expected) {
        return as.post("/api/v1/orgs/" + org + "/invitations", Map.of("email", email, "role", role), expected);
    }

    private static String someone() {
        return "invited-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void ac1_ac7_anInvitedPersonGetsAnAccountAndTheRoleTheyWereGiven() {
        String email = someone();
        JsonNode invitation = invite(owner, email, "bookkeeper", HttpStatus.CREATED);
        String token = invitation.get("token").asText();
        assertThat(invitation.get("status").asText()).isEqualTo("pending");

        // (The closed-sign-up half of AC1 is ClosedSignupInvitationTests: this profile leaves sign-up open
        // so that the other tests can make users at all.)
        ApiClient stranger = new ApiClient(rest, false);
        JsonNode accepted = stranger.post("/api/v1/auth/accept-invitation",
                Map.of("token", token, "displayName", "New Bookkeeper", "password", ApiClient.PASSWORD),
                HttpStatus.OK);
        assertThat(accepted.get("created").asBoolean()).isTrue();
        assertThat(accepted.get("organizationId").asText()).isEqualTo(org);

        JsonNode members = owner.get("/api/v1/orgs/" + org + "/members");
        assertThat(members.toString()).contains(email).contains("bookkeeper");

        // AC7: the trail says who was let in.
        String audit = owner.get("/api/v1/orgs/" + org + "/audit-events").toString();
        assertThat(audit).contains("invitation_created").contains("invitation_accepted");
    }

    @Test
    void ac2_theTokenIsShownOnceAndStoredOnlyAsAHash() {
        String email = someone();
        String token = invite(owner, email, "viewer", HttpStatus.CREATED).get("token").asText();

        JsonNode list = owner.get("/api/v1/orgs/" + org + "/invitations");
        assertThat(list.toString()).doesNotContain(token);
        assertThat(list.get(0).get("token").isNull()).isTrue();

        Integer plain = jdbc.queryForObject("select count(*) from iam.invitation where token_hash = ?",
                Integer.class, token);
        assertThat(plain).as("the raw token is never a stored value").isZero();
        Integer hashed = jdbc.queryForObject("select count(*) from iam.invitation where token_hash = ?",
                Integer.class, InvitationService.hash(token));
        assertThat(hashed).isEqualTo(1);
    }

    @Test
    void ac3_aTokenWorksOnceAndNotAfterItIsRevoked() {
        String first = invite(owner, someone(), "viewer", HttpStatus.CREATED).get("token").asText();
        ApiClient stranger = new ApiClient(rest, false);
        stranger.post("/api/v1/auth/accept-invitation",
                Map.of("token", first, "displayName", "First", "password", ApiClient.PASSWORD), HttpStatus.OK);
        stranger.post("/api/v1/auth/accept-invitation",
                Map.of("token", first, "displayName", "Again", "password", ApiClient.PASSWORD),
                HttpStatus.CONFLICT);

        JsonNode second = invite(owner, someone(), "viewer", HttpStatus.CREATED);
        owner.delete("/api/v1/orgs/" + org + "/invitations/" + second.get("id").asText(), HttpStatus.NO_CONTENT);
        new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", second.get("token").asText(), "displayName", "Late", "password", ApiClient.PASSWORD),
                HttpStatus.CONFLICT);

        new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", "not-a-real-token", "displayName", "Nobody", "password", ApiClient.PASSWORD),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void anExpiredInvitationIsRefusedAndListedAsExpired() {
        JsonNode invitation = invite(owner, someone(), "viewer", HttpStatus.CREATED);
        jdbc.update("update iam.invitation set expires_at = now() - interval '1 day' where id = ?::uuid",
                invitation.get("id").asText());

        new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", invitation.get("token").asText(), "displayName", "Late",
                        "password", ApiClient.PASSWORD),
                HttpStatus.CONFLICT);
        assertThat(owner.get("/api/v1/orgs/" + org + "/invitations").get(0).get("status").asText())
                .isEqualTo("expired");
    }

    @Test
    void ac4_anExistingAccountJustGainsTheMembership() {
        ApiClient other = new ApiClient(rest, false);
        // A second account can only exist because it was invited somewhere first.
        String firstOrgToken = invite(owner, someone(), "viewer", HttpStatus.CREATED).get("token").asText();
        String email = owner.get("/api/v1/orgs/" + org + "/invitations").get(0).get("email").asText();
        other.post("/api/v1/auth/accept-invitation",
                Map.of("token", firstOrgToken, "displayName", "Colleague", "password", ApiClient.PASSWORD),
                HttpStatus.OK);

        String secondOrg = owner.newOrg();
        JsonNode again = owner.post("/api/v1/orgs/" + secondOrg + "/invitations",
                Map.of("email", email, "role", "accountant"), HttpStatus.CREATED);
        JsonNode accepted = new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", again.get("token").asText(), "displayName", "Ignored",
                        "password", "a-completely-different-password"),
                HttpStatus.OK);

        assertThat(accepted.get("created").asBoolean()).as("no new account").isFalse();
        // The original password still works: a link someone was sent cannot change an existing account's password.
        new ApiClient(rest, false).post("/api/v1/auth/login",
                Map.of("email", email, "password", ApiClient.PASSWORD), HttpStatus.OK);
        assertThat(owner.get("/api/v1/orgs/" + secondOrg + "/members").toString()).contains("accountant");
    }

    @Test
    void ac5_ac6_onlyManagersInviteAndOnlyOwnersInviteOwners() {
        // A viewer in this organization cannot invite anyone.
        String viewerToken = invite(owner, someone(), "viewer", HttpStatus.CREATED).get("token").asText();
        ApiClient viewer = new ApiClient(rest, false);
        viewer.post("/api/v1/auth/accept-invitation",
                Map.of("token", viewerToken, "displayName", "Viewer", "password", ApiClient.PASSWORD),
                HttpStatus.OK);
        String viewerEmail = owner.get("/api/v1/orgs/" + org + "/invitations").get(0).get("email").asText();
        ApiClient signedInViewer = new ApiClient(rest, false).loginExisting(viewerEmail, ApiClient.PASSWORD);
        invite(signedInViewer, someone(), "viewer", HttpStatus.FORBIDDEN);

        // AC6: another organization's invitations are not visible at all.
        ApiClient outsider = new ApiClient(rest);
        outsider.get("/api/v1/orgs/" + org + "/invitations", HttpStatus.NOT_FOUND);
    }

    @Test
    void invitingSomeoneWhoIsAlreadyAMemberIsRefused() {
        String token = invite(owner, someone(), "viewer", HttpStatus.CREATED).get("token").asText();
        String email = owner.get("/api/v1/orgs/" + org + "/invitations").get(0).get("email").asText();
        new ApiClient(rest, false).post("/api/v1/auth/accept-invitation",
                Map.of("token", token, "displayName", "Member", "password", ApiClient.PASSWORD), HttpStatus.OK);

        invite(owner, email, "viewer", HttpStatus.CONFLICT);
    }
}
