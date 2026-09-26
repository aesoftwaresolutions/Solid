package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Spec 066. The file that tells {@link DesktopSetup} to skip the bundled database and use someone else's. */
class RemoteDatabaseConfigTests {

    @TempDir
    Path appDir;

    @Test
    void noFileMeansUseTheBundledDatabase() throws IOException {
        assertThat(RemoteDatabaseConfig.load(appDir)).isEmpty();
    }

    /** POSIX asserts; Windows has no POSIX permissions, same reason DesktopModeTests is Linux-only. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void savedConfigRoundTripsAndIsOwnerOnly() throws IOException {
        RemoteDatabaseConfig config = new RemoteDatabaseConfig("db.internal.example", 5433, "solid", "solid_admin",
                "hunter2", RemoteDatabaseConfig.SslMode.VERIFY_FULL);
        config.save(appDir);

        Optional<RemoteDatabaseConfig> loaded = RemoteDatabaseConfig.load(appDir);
        assertThat(loaded).contains(config);

        Path file = appDir.resolve(RemoteDatabaseConfig.FILE_NAME);
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void deletingTheFileGoesBackToTheBundledDatabase() throws IOException {
        new RemoteDatabaseConfig("host", 5432, "solid", "solid", "pw", RemoteDatabaseConfig.SslMode.REQUIRE)
                .save(appDir);
        assertThat(RemoteDatabaseConfig.load(appDir)).isPresent();

        RemoteDatabaseConfig.delete(appDir);

        assertThat(RemoteDatabaseConfig.load(appDir)).isEmpty();
    }

    @Test
    void jdbcUrlHasASslModeAndShortTimeoutsSoATypoFailsFastNotHangs() {
        RemoteDatabaseConfig config = new RemoteDatabaseConfig("10.0.0.5", 5432, "solid", "solid", "pw",
                RemoteDatabaseConfig.SslMode.VERIFY_FULL);
        assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://10.0.0.5:5432/solid?sslmode=verify-full"
                + "&connectTimeout=5&socketTimeout=10");
    }

    @Test
    void maskedNeverCarriesTheRealPassword() {
        RemoteDatabaseConfig config = new RemoteDatabaseConfig("host", 5432, "solid", "solid", "the-real-secret",
                RemoteDatabaseConfig.SslMode.REQUIRE);
        assertThat(config.masked().password()).doesNotContain("the-real-secret");
    }

    @Test
    void requireSaneRejectsWhatItMustToAvoidAConfusingConnectionFailureLater() {
        assertThatThrownBy(() -> new RemoteDatabaseConfig("", 5432, "solid", "solid", "pw",
                RemoteDatabaseConfig.SslMode.REQUIRE).requireSane())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteDatabaseConfig("host", 0, "solid", "solid", "pw",
                RemoteDatabaseConfig.SslMode.REQUIRE).requireSane())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteDatabaseConfig("host", 5432, "", "solid", "pw",
                RemoteDatabaseConfig.SslMode.REQUIRE).requireSane())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteDatabaseConfig("host", 5432, "solid", "", "pw",
                RemoteDatabaseConfig.SslMode.REQUIRE).requireSane())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEnabledFalseFileIsTheSameAsNoFile() throws IOException {
        // A person turned it off from Settings without deleting it, or an older install left one behind.
        Files.writeString(appDir.resolve(RemoteDatabaseConfig.FILE_NAME),
                "enabled=false\nhost=old-server\nport=5432\ndatabase=solid\nusername=solid\npassword=pw\n");

        assertThat(RemoteDatabaseConfig.load(appDir)).isEmpty();
    }

    @Test
    void sslModeParsingFallsBackToRequire() {
        assertThat(RemoteDatabaseConfig.SslMode.parse(null)).isEqualTo(RemoteDatabaseConfig.SslMode.REQUIRE);
        assertThat(RemoteDatabaseConfig.SslMode.parse("nonsense")).isEqualTo(RemoteDatabaseConfig.SslMode.REQUIRE);
        assertThat(RemoteDatabaseConfig.SslMode.parse("disable")).isEqualTo(RemoteDatabaseConfig.SslMode.DISABLE);
        assertThat(RemoteDatabaseConfig.SslMode.parse("verify-full"))
                .isEqualTo(RemoteDatabaseConfig.SslMode.VERIFY_FULL);
    }

    /** POSIX asserts; Windows has no POSIX permissions, same reason DesktopModeTests is Linux-only. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void useTheStaticOwnerOnlyHelperDirectlyAsNonDesktopSetupCodeMust() throws IOException {
        Path file = Files.createFile(appDir.resolve("some-file"));
        assertThat(DesktopSetup.ownerOnlyStatic(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
}
