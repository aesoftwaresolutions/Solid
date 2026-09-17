package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.platform.FieldEncryptor;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 007 acceptance criteria. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthSecurityTests {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FieldEncryptor encryptor;
    @Autowired
    AuditLog audit;

    private static String email() {
        return "sec-" + UUID.randomUUID() + "@example.test";
    }

    private ApiClient anonymous() {
        return new ApiClient(rest, false);
    }

    private String loginToken(ApiClient api, String email) {
        return api.post("/api/v1/auth/login", Map.of("email", email, "password", ApiClient.PASSWORD), HttpStatus.OK)
                .get("token").asText();
    }

    @Test
    void ac1_passwordRulesAndArgon2Storage() {
        ApiClient anon = anonymous();
        anon.post("/api/v1/auth/signup", Map.of("email", email(), "password", "short", "displayName", "X"),
                HttpStatus.BAD_REQUEST);
        anon.post("/api/v1/auth/signup", Map.of("email", "not-an-email", "password", ApiClient.PASSWORD, "displayName", "X"),
                HttpStatus.BAD_REQUEST);

        String email = email();
        anon.post("/api/v1/auth/signup", Map.of("email", email.toUpperCase(), "password", ApiClient.PASSWORD, "displayName", "X"),
                HttpStatus.CREATED);
        anon.post("/api/v1/auth/signup", Map.of("email", email, "password", ApiClient.PASSWORD, "displayName", "X"),
                HttpStatus.CONFLICT);

        String hash = jdbc.queryForObject("select password_hash from iam.user_account where email = ?", String.class, email);
        assertThat(hash).startsWith("$argon2id$").doesNotContain(ApiClient.PASSWORD);
    }

    @Test
    void ac2_everythingRequiresLoginAndMfa() {
        ApiClient anon = anonymous();
        assertThat(anon.get("/api/v1/orgs", HttpStatus.UNAUTHORIZED).get("code").asText()).isEqualTo("UNAUTHENTICATED");
        anon.post("/api/v1/orgs", Map.of("name", "X", "kind", "household"), HttpStatus.UNAUTHORIZED);
        anon.get("/api/v1/tax-lines", HttpStatus.UNAUTHORIZED);
        anon.get("/api/v1/system/info", HttpStatus.OK);

        ApiClient user = new ApiClient(rest);
        ApiClient pending = anonymous().withToken(loginToken(anonymous(), user.email()));
        assertThat(pending.get("/api/v1/orgs", HttpStatus.UNAUTHORIZED).get("code").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(pending.get("/api/v1/auth/me").get("mfaVerified").asBoolean()).isFalse();

        anonymous().withToken("x".repeat(43)).get("/api/v1/orgs", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac3_mfaVerifyAcceptsFreshCodeAndRejectsReplayAndGarbage() {
        ApiClient user = new ApiClient(rest);
        ApiClient second = anonymous().withToken(loginToken(anonymous(), user.email()));

        second.post("/api/v1/auth/mfa/verify", Map.of("code", "000000"), HttpStatus.UNAUTHORIZED);
        long nextStep = Totp.stepAt(Instant.now()) + 1; // activation used the current step
        String code = Totp.codeAt(user.mfaSecret(), nextStep);
        second.post("/api/v1/auth/mfa/verify", Map.of("code", code), HttpStatus.NO_CONTENT);
        second.get("/api/v1/orgs", HttpStatus.OK);

        ApiClient third = anonymous().withToken(loginToken(anonymous(), user.email()));
        assertThat(third.post("/api/v1/auth/mfa/verify", Map.of("code", code), HttpStatus.UNAUTHORIZED)
                .get("code").asText()).isEqualTo("INVALID_MFA_CODE");
    }

    @Test
    void ac4_secretEncryptedAndRecoveryCodesSingleUse() {
        ApiClient anon = anonymous();
        String email = email();
        anon.post("/api/v1/auth/signup", Map.of("email", email, "password", ApiClient.PASSWORD, "displayName", "R"),
                HttpStatus.CREATED);
        ApiClient session = anonymous().withToken(loginToken(anon, email));
        String secret = session.post("/api/v1/auth/mfa/enroll", Map.of(), HttpStatus.OK).get("secret").asText();
        JsonNode activation = session.post("/api/v1/auth/mfa/activate",
                Map.of("code", Totp.codeAt(Base32.decode(secret), Totp.stepAt(Instant.now()))), HttpStatus.OK);
        assertThat(activation.get("recoveryCodes")).hasSize(10);

        String stored = jdbc.queryForObject("select mfa_secret_encrypted from iam.user_account where email = ?", String.class, email);
        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(encryptor.decrypt(stored)).isEqualTo(secret);
        List<String> hashes = jdbc.queryForList("select code_hash from iam.mfa_recovery_code c join iam.user_account u on u.id = c.user_id where u.email = ?", String.class, email);
        assertThat(hashes).hasSize(10).allSatisfy(h -> assertThat(h).startsWith("$argon2id$"));

        String recovery = activation.get("recoveryCodes").get(0).asText();
        ApiClient a = anonymous().withToken(loginToken(anon, email));
        a.post("/api/v1/auth/mfa/verify", Map.of("recoveryCode", recovery), HttpStatus.NO_CONTENT);
        ApiClient b = anonymous().withToken(loginToken(anon, email));
        b.post("/api/v1/auth/mfa/verify", Map.of("recoveryCode", recovery), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac5_accountLocksAfterFiveBadPasswords() {
        ApiClient user = new ApiClient(rest);
        ApiClient anon = anonymous();
        for (int i = 0; i < 5; i++) {
            anon.post("/api/v1/auth/login", Map.of("email", user.email(), "password", "wrong password " + i),
                    HttpStatus.UNAUTHORIZED);
        }
        JsonNode locked = anon.post("/api/v1/auth/login", Map.of("email", user.email(), "password", ApiClient.PASSWORD),
                HttpStatus.LOCKED);
        assertThat(locked.get("code").asText()).isEqualTo("ACCOUNT_LOCKED");

        // Unknown emails get the same generic error.
        assertThat(anon.post("/api/v1/auth/login", Map.of("email", email(), "password", "whatever"), HttpStatus.UNAUTHORIZED)
                .get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void ac5_fiveBadMfaCodesRevokeSession() {
        ApiClient user = new ApiClient(rest);
        ApiClient pending = anonymous().withToken(loginToken(anonymous(), user.email()));
        for (int i = 0; i < 4; i++) {
            pending.post("/api/v1/auth/mfa/verify", Map.of("code", "00000" + i), HttpStatus.UNAUTHORIZED);
        }
        assertThat(pending.post("/api/v1/auth/mfa/verify", Map.of("code", "999999"), HttpStatus.UNAUTHORIZED)
                .get("code").asText()).isEqualTo("SESSION_REVOKED");
        pending.get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac6_tokensHashedIdleTimeoutAndLogout() {
        ApiClient user = new ApiClient(rest);
        Integer plain = jdbc.queryForObject("select count(*) from iam.session where token_hash = ?", Integer.class, user.token());
        assertThat(plain).isZero();

        String idleToken = loginToken(anonymous(), user.email());
        jdbc.update("update iam.session set last_seen_at = now() - interval '31 minutes' where token_hash = ?",
                SessionTokens.hash(idleToken));
        anonymous().withToken(idleToken).get("/api/v1/auth/me", HttpStatus.UNAUTHORIZED);

        user.post("/api/v1/auth/logout", Map.of(), HttpStatus.NO_CONTENT);
        user.get("/api/v1/orgs", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac7_membershipAndRoles() {
        ApiClient owner = new ApiClient(rest);
        ApiClient outsider = new ApiClient(rest);
        ApiClient viewer = new ApiClient(rest);
        String org = owner.newOrg();

        assertThat(owner.get("/api/v1/orgs")).hasSize(1);
        assertThat(outsider.get("/api/v1/orgs")).isEmpty();
        outsider.get("/api/v1/orgs/" + org + "/entities", HttpStatus.NOT_FOUND);
        outsider.post("/api/v1/orgs/" + org + "/entities", Map.of("kind", "individual", "legalName", "X"), HttpStatus.NOT_FOUND);

        owner.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "viewer"), HttpStatus.CREATED);
        viewer.get("/api/v1/orgs/" + org + "/entities", HttpStatus.OK);
        viewer.post("/api/v1/orgs/" + org + "/entities", Map.of("kind", "individual", "legalName", "X"), HttpStatus.FORBIDDEN);
        viewer.get("/api/v1/orgs/" + org + "/audit-events", HttpStatus.FORBIDDEN);
        owner.post("/api/v1/orgs/" + org + "/members", Map.of("email", viewer.email(), "role", "admin"), HttpStatus.CONFLICT);

        JsonNode events = owner.get("/api/v1/orgs/" + org + "/audit-events");
        assertThat(events.findValuesAsText("action")).contains("org_created", "member_added");
    }

    @Test
    void ac8_cookieSessionsNeedCsrfTokenForWrites() {
        ApiClient user = new ApiClient(rest);
        HttpHeaders cookieOnly = new HttpHeaders();
        cookieOnly.add(HttpHeaders.COOKIE, "solid_session=" + user.token());
        cookieOnly.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> read = rest.exchange("/api/v1/orgs", HttpMethod.GET, new HttpEntity<>(cookieOnly), JsonNode.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        String xsrf = read.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow()
                .split(";")[0].substring("XSRF-TOKEN=".length());

        Map<String, String> body = Map.of("name", "Cookie Org", "kind", "household");
        ResponseEntity<JsonNode> noToken = rest.exchange("/api/v1/orgs", HttpMethod.POST, new HttpEntity<>(body, cookieOnly), JsonNode.class);
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders withToken = new HttpHeaders();
        withToken.addAll(cookieOnly);
        withToken.set(HttpHeaders.COOKIE, "solid_session=" + user.token() + "; XSRF-TOKEN=" + xsrf);
        withToken.set("X-XSRF-TOKEN", xsrf);
        ResponseEntity<JsonNode> ok = rest.exchange("/api/v1/orgs", HttpMethod.POST, new HttpEntity<>(body, withToken), JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ac9_auditEventsRecordedWithoutSecretsAndAreAppendOnly() {
        ApiClient user = new ApiClient(rest);
        anonymous().post("/api/v1/auth/login", Map.of("email", user.email(), "password", "definitely wrong"), HttpStatus.UNAUTHORIZED);
        UUID userId = UUID.fromString(user.get("/api/v1/auth/me").get("user").get("id").asText());

        List<String> actions = audit.forUser(userId, 50).stream().map(e -> e.action()).toList();
        assertThat(actions).contains("signup", "login_succeeded", "mfa_enrolled", "login_failed");

        String all = jdbc.queryForList("select details::text from audit.event", String.class).toString();
        assertThat(all).doesNotContain(ApiClient.PASSWORD).doesNotContain(user.token());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from audit.event"))
                .hasStackTraceContaining("permission denied");
        assertThat(audit.verifyChain()).isNull();
    }

    @Test
    void ac10_securityHeaders() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/system/info", String.class);
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeaders().getFirst("Cache-Control")).contains("no-store");
    }
}
