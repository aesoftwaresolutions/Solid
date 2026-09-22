package com.aesoftwaresolutions.solid.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.iam.Base32;
import com.aesoftwaresolutions.solid.iam.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Helper for API tests: signs up a fresh user with MFA, then sends JSON with a bearer token, asserts the
 * status and returns the body as a JSON tree.
 */
public class ApiClient {

    public static final String PASSWORD = "correct horse battery staple";

    private final TestRestTemplate http;
    private String token;
    private String email;
    private byte[] mfaSecret;

    /** Creates and logs in a brand-new MFA-verified user. */
    public ApiClient(TestRestTemplate http) {
        this(http, true);
    }

    public ApiClient(TestRestTemplate http, boolean login) {
        this.http = http;
        // JDK client supports PATCH.
        this.http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        if (login) {
            signupAndLogin("user-" + UUID.randomUUID() + "@example.test");
        }
    }

    public ApiClient signupAndLogin(String newEmail) {
        this.email = newEmail;
        this.token = null;
        post("/api/v1/auth/signup", Map.of("email", newEmail, "password", PASSWORD, "displayName", "Test User"),
                HttpStatus.CREATED);
        this.token = post("/api/v1/auth/login", Map.of("email", newEmail, "password", PASSWORD), HttpStatus.OK)
                .get("token").asText();
        this.mfaSecret = Base32.decode(post("/api/v1/auth/mfa/enroll", Map.of(), HttpStatus.OK).get("secret").asText());
        post("/api/v1/auth/mfa/activate", Map.of("code", Totp.codeAt(mfaSecret, Totp.stepAt(Instant.now()))), HttpStatus.OK);
        return this;
    }

    /**
     * Signs in a user who already exists — the case an invited person is in (spec 046). A brand-new invited
     * account has no MFA yet, so this enrols it the way the real screens would.
     */
    public ApiClient loginExisting(String existingEmail, String password) {
        return loginExisting(existingEmail, password, null);
    }

    /**
     * @param knownSecret that user's MFA secret when they already have one (a second session for the same
     *                    person); null for an account that has never enrolled, which this then enrols
     */
    public ApiClient loginExisting(String existingEmail, String password, byte[] knownSecret) {
        this.email = existingEmail;
        JsonNode login = post("/api/v1/auth/login", Map.of("email", existingEmail, "password", password),
                HttpStatus.OK);
        this.token = login.get("token").asText();
        if (login.get("mfaEnrolled").asBoolean()) {
            if (knownSecret == null) {
                throw new IllegalArgumentException("That account already has MFA; pass its secret");
            }
            this.mfaSecret = knownSecret;
            // A code can only be used once, and this user's other sessions may have spent the codes around
            // now, so walk forward through the accepted window until one is accepted.
            verifyWithAFreshCode();
        } else {
            this.mfaSecret = Base32.decode(post("/api/v1/auth/mfa/enroll", Map.of(), HttpStatus.OK)
                    .get("secret").asText());
            post("/api/v1/auth/mfa/activate", Map.of("code", Totp.codeAt(mfaSecret, Totp.stepAt(Instant.now()))),
                    HttpStatus.OK);
        }
        return this;
    }

    /**
     * A TOTP code is good once per user, and the server only accepts the step before, the current one and
     * the next. When this user's other sessions have already spent those, the only thing to do is wait for
     * the clock to move on — which a second session for the same person legitimately has to do.
     */
    private void verifyWithAFreshCode() {
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode problem = exchangeAllowing(HttpMethod.POST, "/api/v1/auth/mfa/verify",
                    Map.of("code", Totp.codeAt(mfaSecret, Totp.stepAt(Instant.now()) + 1)));
            if (problem == null) {
                return;
            }
            sleepToNextStep();
        }
        throw new IllegalStateException("Could not verify MFA with a fresh code");
    }

    private static void sleepToNextStep() {
        try {
            Thread.sleep((31 - (System.currentTimeMillis() / 1000) % 30) * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns null when the call succeeded, or the problem body when it was refused with 401. */
    private JsonNode exchangeAllowing(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers),
                JsonNode.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            return null;
        }
        assertThat(response.getStatusCode()).as("MFA verify -> " + response.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        return response.getBody();
    }

    public String email() {
        return email;
    }

    public String token() {
        return token;
    }

    public byte[] mfaSecret() {
        return mfaSecret;
    }

    public ApiClient withToken(String newToken) {
        this.token = newToken;
        return this;
    }

    /** Multipart upload of one file with PUT, for endpoints that replace a single stored file. */
    public JsonNode putFile(String path, String filename, byte[] content, HttpStatus expected) {
        return sendFile(HttpMethod.PUT, path, filename, content, expected);
    }

    /** Multipart upload of one file, for the import and document endpoints. */
    public JsonNode postFile(String path, String filename, byte[] content, HttpStatus expected) {
        return sendFile(HttpMethod.POST, path, filename, content, expected);
    }

    private JsonNode sendFile(HttpMethod method, String path, String filename, byte[] content,
                              HttpStatus expected) {
        org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(form, headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).as(method + " " + path + " -> " + response.getBody())
                .isEqualTo(expected);
        return response.getBody();
    }

    public JsonNode post(String path, Object body, HttpStatus expected) {
        return exchange(HttpMethod.POST, path, body, expected);
    }

    public JsonNode patch(String path, Object body, HttpStatus expected) {
        return exchange(HttpMethod.PATCH, path, body, expected);
    }

    public JsonNode put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body, HttpStatus.OK);
    }

    public JsonNode put2(String path, Object body, HttpStatus expected) {
        return exchange(HttpMethod.PUT, path, body, expected);
    }

    public JsonNode get(String path, HttpStatus expected) {
        return exchange(HttpMethod.GET, path, null, expected);
    }

    public JsonNode get(String path) {
        return get(path, HttpStatus.OK);
    }

    public JsonNode delete(String path, HttpStatus expected) {
        return exchange(HttpMethod.DELETE, path, null, expected);
    }

    public String newOrg() {
        return post("/api/v1/orgs", Map.of("name", "Test Org", "kind", "business"), HttpStatus.CREATED)
                .get("id").asText();
    }

    public String newEntity(String orgId, String kind) {
        return post("/api/v1/orgs/" + orgId + "/entities", Map.of("kind", kind, "legalName", "Test " + kind),
                HttpStatus.CREATED).get("id").asText();
    }

    private JsonNode exchange(HttpMethod method, String path, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).as(method + " " + path + " -> " + response.getBody()).isEqualTo(expected);
        return response.getBody();
    }
}
