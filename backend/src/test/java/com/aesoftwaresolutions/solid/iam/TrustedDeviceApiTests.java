package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;

/** Spec 070: a trusted device skips the authenticator code for a bounded time after proving it once. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TrustedDeviceApiTests {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient user;

    @BeforeEach
    void setUp() {
        user = new ApiClient(rest);
    }

    /** A fresh sign-in of the fixture user, optionally carrying a device cookie. */
    private ResponseEntity<JsonNode> login(String deviceCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (deviceCookie != null) {
            headers.add(HttpHeaders.COOKIE, "solid_device=" + deviceCookie);
        }
        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", user.email(), "password", ApiClient.PASSWORD), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private String tokenOf(ResponseEntity<JsonNode> login) {
        return login.getBody().get("token").asText();
    }

    private String deviceCookieOf(String bearer) {
        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/auth/mfa/trusted-device", HttpMethod.POST,
                new HttpEntity<>(headersOf(bearer)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setCookie = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("solid_device=")).findFirst().orElse(null);
        assertThat(setCookie).as("the device token is only ever in the cookie header").isNotNull();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth");
        return setCookie.split(";")[0].substring("solid_device=".length());
    }

    private HttpHeaders headersOf(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return headers;
    }

    private int trustedCheck(String bearer, String deviceCookie) {
        HttpHeaders headers = headersOf(bearer);
        if (deviceCookie != null) {
            headers.add(HttpHeaders.COOKIE, "solid_device=" + deviceCookie);
        }
        return rest.exchange("/api/v1/auth/mfa/trusted-check", HttpMethod.POST, new HttpEntity<>(headers),
                JsonNode.class).getStatusCode().value();
    }

    @Test
    void ac1_ac4_afterProvingTheCodeOnceThisMachineSkipsItNextTime() {
        // First sign-in: code needed, earned, device trusted.
        String session1 = tokenOf(login(null));
        assertThat(trustedCheck(session1, null)).as("nothing trusted yet").isEqualTo(404);
        String bearer1 = freshCodeSession();
        String deviceCookie = deviceCookieOf(bearer1);

        Integer plain = jdbc.queryForObject(
                "select count(*) from iam.trusted_device where token_hash = ?", Integer.class, deviceCookie);
        assertThat(plain).as("only the hash is stored").isZero();

        // Next sign-in, same machine: password is enough.
        String session2 = tokenOf(login(deviceCookie));
        assertThat(trustedCheck(session2, deviceCookie)).isEqualTo(204);

        assertThat(rest.exchange("/api/v1/orgs", HttpMethod.GET, new HttpEntity<>(headersOf(session2)),
                JsonNode.class).getStatusCode()).as("no code screen this time").isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac2_anUntrustedOrGarbageCookieChangesNothing() {
        String session = tokenOf(login(null));
        assertThat(trustedCheck(session, "x".repeat(43))).isEqualTo(404);

        assertThat(rest.exchange("/api/v1/orgs", HttpMethod.GET, new HttpEntity<>(headersOf(session)),
                JsonNode.class).getStatusCode()).as("still needs the code").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac3_aDeviceTokenForSomeoneElseDoesNothing() {
        String bearer1 = freshCodeSession();
        String otherDeviceCookie = deviceCookieOf(bearer1);

        ApiClient outsider = new ApiClient(rest);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "solid_device=" + otherDeviceCookie);
        ResponseEntity<JsonNode> login = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", outsider.email(), "password", ApiClient.PASSWORD), headers),
                JsonNode.class);
        String outsiderBearer = login.getBody().get("token").asText();

        assertThat(trustedCheck(outsiderBearer, otherDeviceCookie))
                .as("it belongs to another account").isEqualTo(404);
    }

    @Test
    void ac5_forgettingAllDevicesEndsTheShortcutImmediately() {
        String bearer = freshCodeSession();
        String deviceCookie = deviceCookieOf(bearer);

        int status = rest.exchange("/api/v1/auth/mfa/trusted-devices", HttpMethod.DELETE,
                new HttpEntity<>(headersOf(user.token())), JsonNode.class).getStatusCode().value();
        assertThat(status).isEqualTo(200);

        String session = tokenOf(login(deviceCookie));
        assertThat(trustedCheck(session, deviceCookie)).as("forgotten before it is used").isEqualTo(404);
    }

    @Test
    void ac6_anExpiredTokenIsJustAnotherUnknownDevice() {
        String bearer = freshCodeSession();
        String deviceCookie = deviceCookieOf(bearer);
        jdbc.update("update iam.trusted_device set expires_at = now() - interval '1 day' where token_hash = ?",
                SessionTokens.hash(deviceCookie));

        String session = tokenOf(login(deviceCookie));
        assertThat(trustedCheck(session, deviceCookie)).isEqualTo(404);
    }

    /**
     * Security: the password alone must never be enough. A session still waiting for its code may not mint a
     * device token, or it could trust itself and skip the code it never entered.
     */
    @Test
    void sec1_aSessionThatHasNotProvenTheCodeCannotTrustItself() {
        String pending = tokenOf(login(null));
        ResponseEntity<JsonNode> minted = rest.exchange("/api/v1/auth/mfa/trusted-device", HttpMethod.POST,
                new HttpEntity<>(headersOf(pending)), JsonNode.class);
        assertThat(minted.getStatusCode()).as("no device token before the code").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(minted.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(c -> c.startsWith("solid_device="));
        assertThat(jdbc.queryForObject("select count(*) from iam.trusted_device where user_id = "
                + "(select id from iam.user_account where email = ?)", Integer.class, user.email())).isZero();

        assertThat(rest.exchange("/api/v1/orgs", HttpMethod.GET, new HttpEntity<>(headersOf(pending)),
                JsonNode.class).getStatusCode()).as("still needs the code").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sec2_skippingTheCodeDoesNotEarnTheNextMonth() {
        String deviceCookie = deviceCookieOf(freshCodeSession());
        String viaDevice = tokenOf(login(deviceCookie));
        assertThat(trustedCheck(viaDevice, deviceCookie)).isEqualTo(204);

        ResponseEntity<JsonNode> again = rest.exchange("/api/v1/auth/mfa/trusted-device", HttpMethod.POST,
                new HttpEntity<>(headersOf(viaDevice)), JsonNode.class);
        assertThat(again.getStatusCode()).as("only a typed code earns a device token").isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asText()).isEqualTo("CODE_REQUIRED");
    }

    @Test
    void sec3_changingThePasswordForgetsEveryTrustedComputer() {
        String bearer = freshCodeSession();
        String deviceCookie = deviceCookieOf(bearer);

        HttpHeaders headers = headersOf(bearer);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String newPassword = "a different horse battery staple";
        assertThat(rest.exchange("/api/v1/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", ApiClient.PASSWORD, "newPassword", newPassword), headers),
                JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(jdbc.queryForObject("select count(*) from iam.trusted_device where user_id = "
                + "(select id from iam.user_account where email = ?)", Integer.class, user.email())).isZero();
    }

    /** Logs in fresh and verifies with a next-step code, the way a real browser does after signing in. */
    private String freshCodeSession() {
        ResponseEntity<JsonNode> login = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", user.email(), "password", ApiClient.PASSWORD)), JsonNode.class);
        String bearer = login.getBody().get("token").asText();
        String code = Totp.codeAt(user.mfaSecret(), Totp.stepAt(Instant.now()) + 1);
        ResponseEntity<JsonNode> verify = rest.exchange("/api/v1/auth/mfa/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("code", code), headersOf(bearer)), JsonNode.class);
        if (verify.getStatusCode() != HttpStatus.NO_CONTENT) {
            // The same step may already be spent on this shared clock; move to the next one.
            code = Totp.codeAt(user.mfaSecret(), Totp.stepAt(Instant.now()) + 2);
            verify = rest.exchange("/api/v1/auth/mfa/verify", HttpMethod.POST,
                    new HttpEntity<>(Map.of("code", code), headersOf(bearer)), JsonNode.class);
        }
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return bearer;
    }
}
