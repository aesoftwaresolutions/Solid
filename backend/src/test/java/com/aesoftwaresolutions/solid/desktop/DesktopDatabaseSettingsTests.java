package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spec 066. Settings, for the desktop build: point Solid at a PostgreSQL server other than the bundled one.
 * Runs the same way {@link DesktopModeTests} does, sharing its reasons.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ActiveProfiles("desktop")
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class DesktopDatabaseSettingsTests {

    private static final Path APP_DIR = tempAppDir();

    private static Path tempAppDir() {
        try {
            Path dir = Files.createTempDirectory("solid-desktop-db-test");
            System.setProperty("solid.desktop.home", dir.toString());
            System.setProperty("solid.desktop.port", "0");
            System.setProperty("solid.desktop.open-browser", "false");
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Could not make a temporary app folder", e);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    TestRestTemplate rest;

    @org.springframework.beans.factory.annotation.Autowired
    JdbcClient db;

    /**
     * This class shares its embedded database with {@link DesktopModeTests} (same profile and web environment,
     * so Spring reuses one context for both), which signs up users of its own — so "first account ever" is
     * already taken before this class's tests run. Each test here makes its own account and promotes it
     * directly, the same way {@code BackupStatusApiTests} does, instead of relying on signup order.
     */
    private ApiClient admin() {
        ApiClient client = new ApiClient(rest);
        db.sql("update iam.user_account set is_instance_admin = true where email = ?").param(client.email())
                .update();
        return client;
    }

    @Test
    void ac1_anonymousAndOrdinaryUsersCannotSeeOrChangeIt() {
        ApiClient anonymous = new ApiClient(rest, false);
        assertThat(anonymous.attempt(HttpMethod.GET, "/api/v1/desktop/database", null)).isEqualTo(401);

        admin(); // in case this test runs against a fresh database, claim the first-signup-is-admin slot first

        // An ordinary (non-admin) signup is not promoted, so it must be refused.
        ApiClient ordinary = new ApiClient(rest);
        assertThat(ordinary.attempt(HttpMethod.GET, "/api/v1/desktop/database", null)).isEqualTo(403);
        assertThat(ordinary.attempt(HttpMethod.PUT, "/api/v1/desktop/database",
                Map.of("host", "x", "port", 5432, "database", "solid", "username", "solid", "password", "pw",
                        "sslMode", "require")))
                .isEqualTo(403);
    }

    @Test
    void ac2_startsOnTheBundledDatabaseAndSaysSo() {
        ApiClient admin = admin();
        JsonNode status = admin.get("/api/v1/desktop/database", HttpStatus.OK);
        assertThat(status.get("mode").asText()).isEqualTo("bundled");
        assertThat(status.get("current").isNull()).isTrue();
    }

    @Test
    void ac3_testingAConnectionToNowhereFailsInsteadOfHangingAndSavesNothing() {
        ApiClient admin = admin();
        JsonNode result = admin.post("/api/v1/desktop/database/test",
                Map.of("host", "192.0.2.1", "port", 5432, "database", "solid", "username", "solid",
                        "password", "wrong", "sslMode", "disable"),
                HttpStatus.OK);
        assertThat(result.get("ok").asBoolean()).isFalse();

        // A failed test must not have written anything: still on the bundled database.
        assertThat(admin.get("/api/v1/desktop/database", HttpStatus.OK).get("mode").asText()).isEqualTo("bundled");
    }

    @Test
    void ac4_savingRefusesAConnectionThatDoesNotWork() {
        ApiClient admin = admin();
        // ApiClient has no put-with-expected-problem-body helper; attempt() is enough: the refusal status is
        // what locks anyone out or not, and the mode assertion below proves nothing was saved.
        assertThat(admin.attempt(HttpMethod.PUT, "/api/v1/desktop/database",
                Map.of("host", "192.0.2.1", "port", 5432, "database", "solid", "username", "solid",
                        "password", "wrong", "sslMode", "disable")))
                .isEqualTo(409);
        assertThat(admin.get("/api/v1/desktop/database", HttpStatus.OK).get("mode").asText()).isEqualTo("bundled");
    }

    @Test
    void ac5_resettingWithNothingSavedIsHarmless() {
        ApiClient admin = admin();
        JsonNode result = admin.delete("/api/v1/desktop/database", HttpStatus.OK);
        assertThat(result.get("restartRequired").asBoolean()).isTrue();
        assertThat(admin.get("/api/v1/desktop/database", HttpStatus.OK).get("mode").asText()).isEqualTo("bundled");
    }
}
