package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 048. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PasswordApiTests {

    private static final String NEW_PASSWORD = "a brand new passphrase";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordService passwords;

    @Autowired
    IamService iam;

    ApiClient user;

    @BeforeEach
    void setUp() {
        user = new ApiClient(rest);
    }

    private String userId() {
        return jdbc.queryForObject("select id::text from iam.user_account where email = ?", String.class,
                user.email());
    }

    @Test
    void ac1_ac2_changingYourPasswordWorksAndEndsYourOtherSessions() {
        // A second session for the same person, as if they were also signed in elsewhere.
        ApiClient elsewhere = new ApiClient(rest, false)
                .loginExisting(user.email(), ApiClient.PASSWORD, user.mfaSecret());
        elsewhere.get("/api/v1/auth/me", HttpStatus.OK);

        user.post("/api/v1/auth/change-password",
                Map.of("currentPassword", ApiClient.PASSWORD, "newPassword", NEW_PASSWORD),
                HttpStatus.NO_CONTENT);

        // AC2: this session still works, the other one does not.
        user.get("/api/v1/auth/me", HttpStatus.OK);
        elsewhere.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);

        // AC1: the new password works, the old one does not.
        new ApiClient(rest, false).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", NEW_PASSWORD), HttpStatus.OK);
        new ApiClient(rest, false).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", ApiClient.PASSWORD), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac3_theWrongCurrentPasswordChangesNothing() {
        user.post("/api/v1/auth/change-password",
                Map.of("currentPassword", "not my password", "newPassword", NEW_PASSWORD),
                HttpStatus.UNAUTHORIZED);
        user.post("/api/v1/auth/change-password",
                Map.of("currentPassword", ApiClient.PASSWORD, "newPassword", ApiClient.PASSWORD),
                HttpStatus.BAD_REQUEST);

        new ApiClient(rest, false).post("/api/v1/auth/login",
                Map.of("email", user.email(), "password", ApiClient.PASSWORD), HttpStatus.OK);
    }

    @Test
    void ac4_ac5_ac7_ac9_anAdministratorIssuesALinkThatWorksOnce() {
        // The very first account on the instance is the administrator; here we grant it for the test's user.
        jdbc.update("update iam.user_account set is_instance_admin = true where email = ?", user.email());
        ApiClient admin = new ApiClient(rest, false)
                .loginExisting(user.email(), ApiClient.PASSWORD, user.mfaSecret());

        ApiClient forgetful = new ApiClient(rest);
        String forgetfulId = jdbc.queryForObject("select id::text from iam.user_account where email = ?",
                String.class, forgetful.email());

        JsonNode reset = admin.post("/api/v1/instance/users/" + forgetfulId + "/password-reset", Map.of(),
                HttpStatus.OK);
        String token = reset.get("token").asText();
        assertThat(reset.get("email").asText()).isEqualTo(forgetful.email());

        ApiClient anonymous = new ApiClient(rest, false);
        anonymous.post("/api/v1/auth/reset-password", Map.of("token", token, "newPassword", NEW_PASSWORD),
                HttpStatus.NO_CONTENT);
        // AC4: once, and only once.
        anonymous.post("/api/v1/auth/reset-password", Map.of("token", token, "newPassword", "another one here"),
                HttpStatus.CONFLICT);

        // AC7: the reset signs nobody in, and MFA is still required.
        JsonNode login = anonymous.post("/api/v1/auth/login",
                Map.of("email", forgetful.email(), "password", NEW_PASSWORD), HttpStatus.OK);
        assertThat(login.get("mfaEnrolled").asBoolean()).isTrue();
        ApiClient afterReset = new ApiClient(rest, false).withToken(login.get("token").asText());
        afterReset.get("/api/v1/auth/me", HttpStatus.OK);
        // Signed in but not past the second factor: organization data stays shut (MFA_REQUIRED).
        afterReset.get("/api/v1/orgs", HttpStatus.UNAUTHORIZED);

        // AC9: the trail records the issue and the use, and never the token.
        String audit = jdbc.queryForList("select action, details::text from audit.event order by seq desc limit 40")
                .toString();
        assertThat(audit).contains("password_reset_issued").contains("password_reset_used");
        assertThat(audit).doesNotContain(token);
    }

    @Test
    void ac5_anExpiredOrSupersededLinkIsRefused() {
        jdbc.update("update iam.user_account set is_instance_admin = true where email = ?", user.email());
        ApiClient admin = new ApiClient(rest, false)
                .loginExisting(user.email(), ApiClient.PASSWORD, user.mfaSecret());
        ApiClient forgetful = new ApiClient(rest);
        String forgetfulId = jdbc.queryForObject("select id::text from iam.user_account where email = ?",
                String.class, forgetful.email());

        String stale = admin.post("/api/v1/instance/users/" + forgetfulId + "/password-reset", Map.of(),
                HttpStatus.OK).get("token").asText();
        String current = admin.post("/api/v1/instance/users/" + forgetfulId + "/password-reset", Map.of(),
                HttpStatus.OK).get("token").asText();

        // Issuing a new link retires the old one.
        new ApiClient(rest, false).post("/api/v1/auth/reset-password",
                Map.of("token", stale, "newPassword", NEW_PASSWORD), HttpStatus.CONFLICT);

        jdbc.update("update iam.password_reset set expires_at = now() - interval '1 hour' where token_hash = ?",
                InvitationService.hash(current));
        new ApiClient(rest, false).post("/api/v1/auth/reset-password",
                Map.of("token", current, "newPassword", NEW_PASSWORD), HttpStatus.CONFLICT);
    }

    @Test
    void ac6_ordinaryUsersCannotListPeopleOrIssueResets() {
        ApiClient ordinary = new ApiClient(rest);
        ordinary.get("/api/v1/instance/users", HttpStatus.FORBIDDEN);
        ordinary.post("/api/v1/instance/users/" + userId() + "/password-reset", Map.of(), HttpStatus.FORBIDDEN);
        new ApiClient(rest, false).get("/api/v1/instance/users", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac8_theCommandLinePathIssuesALinkWithoutAnyLogin() {
        // What --solid.reset-password=<email> does once it has found the account: issue with no issuer.
        ApiClient forgetful = new ApiClient(rest);
        IamService.User found = iam.findUserByEmail(forgetful.email()).orElseThrow();

        PasswordService.Reset reset = passwords.issueReset(found.id(), null, "command line");

        new ApiClient(rest, false).post("/api/v1/auth/reset-password",
                Map.of("token", reset.token(), "newPassword", NEW_PASSWORD), HttpStatus.NO_CONTENT);
        new ApiClient(rest, false).post("/api/v1/auth/login",
                Map.of("email", forgetful.email(), "password", NEW_PASSWORD), HttpStatus.OK);
    }
}
