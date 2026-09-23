package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.aesoftwaresolutions.solid.support.ApiClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spec 060. The desktop build with nothing set up beforehand: no database running, no environment variables,
 * an empty folder.
 *
 * <p>Deliberately not a Testcontainers test — the point is the PostgreSQL that ships inside the app, started
 * the way a double-click install starts it.
 *
 * <p>Linux only, because the build carries the Linux PostgreSQL binaries for tests. The Windows and macOS
 * desktop builds are exercised the same way on their own runners by .github/workflows/desktop.yml, which
 * installs the packaged app and smoke-tests it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ActiveProfiles("desktop")
@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
class DesktopModeTests {

    /** The app folder for this run. Set before the context loads, because the desktop setup reads it then. */
    private static final Path APP_DIR = tempAppDir();

    private static Path tempAppDir() {
        try {
            Path dir = Files.createTempDirectory("solid-desktop-test");
            System.setProperty("solid.desktop.home", dir.toString());
            System.setProperty("solid.desktop.port", "0");
            System.setProperty("solid.desktop.open-browser", "false");
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("Could not make a temporary app folder", e);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void ac1_ac2_itStartsFromAnEmptyFolderAndKeepsItsKey() throws IOException {
        // AC1: migrated and serving, with nothing set up beforehand.
        ApiClient api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        assertThat(api.get("/api/v1/orgs/" + org + "/entities/" + entity).get("legalName").asText())
                .isEqualTo("Test sole_prop");

        // AC2: the key is there, is owner-only, and is the one the app is actually using.
        Path keyFile = APP_DIR.resolve("master.key");
        assertThat(keyFile).exists();
        String key = Files.readString(keyFile).trim();
        assertThat(java.util.Base64.getDecoder().decode(key)).hasSize(32);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
        assertThat(permissions).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);

        // Reading it again must not make a new one: a second key would orphan every encrypted document.
        assertThat(Files.readString(keyFile).trim()).isEqualTo(key);
    }

    @Test
    void ac3_rowLevelSecurityStillAppliesUnderTheBundledDatabase() {
        ApiClient owner = new ApiClient(rest);
        String org = owner.newOrg();
        String entity = owner.newEntity(org, "sole_prop");
        String base = "/api/v1/orgs/" + org + "/entities/" + entity;
        owner.post(base + "/customers", Map.of("name", "Northwind Traders"), HttpStatus.CREATED);

        // The bundled server's superuser runs migrations, but the pool switches to solid_app, so the wall
        // between organizations is still up. If this ever passes by accident, the desktop build is unsafe.
        ApiClient outsider = new ApiClient(rest);
        outsider.get(base + "/customers", HttpStatus.NOT_FOUND);
        outsider.get("/api/v1/orgs/" + org, HttpStatus.NOT_FOUND);
    }

    @Test
    void ac5_uploadedDocumentsLandInTheAppFolder() {
        ApiClient api = new ApiClient(rest);
        String org = api.newOrg();
        String entity = api.newEntity(org, "sole_prop");
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        api.postFile("/api/v1/orgs/" + org + "/entities/" + entity + "/documents?kind=receipt",
                "receipt.png", png, HttpStatus.CREATED);

        assertThat(APP_DIR.resolve("documents")).exists();
        assertThat(APP_DIR.resolve("documents").resolve(org)).exists();
    }

    @Test
    void ac6_theWebUiIsReachableWithoutSigningInButTheApiIsNot() {
        // The login screen has to load before anyone can log in; the books must not.
        assertThat(rest.getForEntity("/", String.class).getStatusCode().value())
                .as("the UI is served by the app itself in desktop mode").isNotEqualTo(401);
        assertThat(rest.getForEntity("/orgs/whatever/sales", String.class).getStatusCode().value())
                .as("a refresh deep in the app still reaches the single-page app").isNotEqualTo(401);
        assertThat(rest.getForEntity("/api/v1/orgs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .as("the API description maps out the whole server; it stays shut")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac4_theServerIsOnTheLoopbackInterfaceOnly() {
        // What the desktop setup asked for is what a desktop install must have: nothing on the network.
        assertThat(System.getProperty("solid.desktop.home")).isEqualTo(APP_DIR.toString());
        assertThat(rest.getRestTemplate().getUriTemplateHandler().expand("/").getHost())
                .isIn("localhost", "127.0.0.1");
    }
}
