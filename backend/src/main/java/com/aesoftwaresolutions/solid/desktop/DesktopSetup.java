package com.aesoftwaresolutions.solid.desktop;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Everything the desktop build needs that a server install gets from its operator: a database, a master key,
 * somewhere to put files (spec 060).
 *
 * <p>This runs as an {@link EnvironmentPostProcessor}, before any bean exists, because the database has to be
 * listening before Spring builds a connection pool or Flyway asks it for a connection. It does nothing at all
 * unless the {@code desktop} profile is active, so a server install is untouched by its presence.
 *
 * <p>The database really is PostgreSQL, started from binaries shipped inside the app. That is not a detail:
 * Solid's isolation between organizations is PostgreSQL row-level security plus the non-superuser
 * {@code solid_app} role, and no lighter database has either.
 */
public class DesktopSetup implements EnvironmentPostProcessor {

    /**
     * This runs before Spring Boot has configured logging, so an ordinary logger would write into nothing —
     * and everything said here is worth reading: where the books are, that a master key was just made and
     * the folder needs backing up, that a database from an earlier run had to be stopped. A deferred log is
     * replayed once logging is up (spec 064).
     */
    private final Log log;

    public DesktopSetup(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DesktopSetup.class);
    }

    /** The port the desktop app answers on. Fixed, so the shortcut and the browser always agree. */
    public static final int PORT = 18080;

    private static FileLock lock;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean desktop = environment.getProperty("solid.desktop.enabled", Boolean.class, false)
                || Set.of(environment.getActiveProfiles()).contains("desktop");
        if (!desktop) {
            return;
        }
        Path appDir = appDir(environment);
        try {
            Files.createDirectories(appDir);
            takeSingleInstanceLock(appDir);

            Map<String, Object> properties = new HashMap<>();
            properties.put("solid.security.master-key", masterKey(appDir));
            properties.put("solid.documents.root", appDir.resolve("documents").toString());
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", environment.getProperty("solid.desktop.port", Integer.class, PORT));
            properties.putAll(database(appDir));
            environment.getPropertySources().addFirst(new MapPropertySource("solid-desktop", properties));
            log.info("Solid desktop: everything lives in " + appDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not set up the Solid folder at " + appDir, e);
        }
    }

    /** Where one person's books live: the platform's own place for application data. */
    static Path appDir(ConfigurableEnvironment environment) {
        String configured = environment.getProperty("solid.desktop.home");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return (localAppData == null || localAppData.isBlank() ? home.resolve("AppData").resolve("Local")
                    : Path.of(localAppData)).resolve("Solid");
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve("Solid");
        }
        return home.resolve(".local").resolve("share").resolve("solid");
    }

    /**
     * Reads the key this installation has always used, or makes one the first time.
     *
     * <p>Generating a second key would silently orphan every encrypted document, so this only ever writes
     * when there is no file, and the file is owner-only where the OS can say so.
     */
    private String masterKey(Path appDir) throws IOException {
        Path keyFile = appDir.resolve("master.key");
        if (Files.exists(keyFile)) {
            return Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Files.writeString(keyFile, encoded + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        ownerOnly(keyFile);
        log.info("Solid desktop: made a new master key at " + keyFile + ". Back up this whole folder — "
                + "without the key, encrypted documents cannot be read.");
        return encoded;
    }

    /**
     * Makes a file readable by this user alone.
     *
     * <p>On Linux and macOS that is mode 600. Windows has no such modes, and the key used to get no protection of
     * its own there — only whatever the folder above it happened to allow (spec 065, row 10). There, the file's
     * access list is replaced with a single entry: full control for the user running Solid, and nobody else.
     */
    private void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system: Windows. Fall through to its access list.
        }
        java.nio.file.attribute.AclFileAttributeView acl =
                Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl == null) {
            log.warn("Could not restrict " + file + " to this user; protect the Solid folder yourself.");
            return;
        }
        try {
            java.nio.file.attribute.UserPrincipal me;
            try {
                // The person running Solid — not the file's owner, which for an elevated process can be the
                // whole Administrators group.
                me = file.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (IOException notFound) {
                me = Files.getOwner(file);
            }
            java.nio.file.attribute.AclEntry onlyMe = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(me)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .build();
            acl.setAcl(java.util.List.of(onlyMe));
        } catch (IOException | SecurityException e) {
            log.warn("Could not restrict " + file + " to this user; protect the Solid folder yourself.");
        }
    }

    /**
     * A secret kept in one file in the app folder, made the first time it is asked for: owner-only, never
     * regenerated while the file exists. Returns the value and whether it was just made.
     */
    private Secret secret(Path file, String what) throws IOException {
        if (Files.exists(file)) {
            return new Secret(Files.readString(file, StandardCharsets.UTF_8).trim(), false);
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        // Hex: nothing in it needs quoting in SQL or escaping in a connection string.
        String value = java.util.HexFormat.of().formatHex(random);
        Files.writeString(file, value + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        ownerOnly(file);
        log.info("Solid desktop: made a new " + what + " at " + file + ".");
        return new Secret(value, true);
    }

    private record Secret(String value, boolean isNew) {
    }

    /**
     * Starts the bundled PostgreSQL against this installation's data directory and points Spring at it.
     *
     * <p>The server listens on a loopback port, and every account on the machine can reach a loopback port. The
     * library that sets PostgreSQL up creates it with {@code trust} login, meaning whoever reached the port
     * could connect as the superuser — and a superuser walks straight past row-level security (spec 065, row 2).
     * So the superuser gets a password only this installation knows ({@code db.password}, owner-only, beside
     * the master key), and the server's access rules are rewritten to demand it from every connection.
     */
    private Map<String, Object> database(Path appDir) throws IOException {
        Path dataDir = appDir.resolve("db");
        Path hba = dataDir.resolve("pg_hba.conf");
        Secret password = secret(appDir.resolve("db.password"), "database password");
        if (password.isNew() && Files.exists(hba)) {
            // An install from before this rule, or one whose password file went missing: open the door once,
            // on loopback only, so the new password can be set. It is closed again moments later, below.
            Files.writeString(hba, ACCESS_WHILE_SETTING_PASSWORD, StandardCharsets.UTF_8);
        }

        // A copy killed outright leaves its server holding this directory; take it over before starting.
        DesktopShutdown.stopAnyServerStillRunning(dataDir, log);
        EmbeddedPostgres started = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(appDir.resolve("pgbin").toFile())
                // Loopback only: a desktop install is for the person at this machine.
                .setServerConfig("listen_addresses", "127.0.0.1")
                // The library checks the server is up by connecting; once access needs the password, so does it.
                .setConnectConfig("password", password.value())
                .start();
        DesktopShutdown.remember(started, dataDir);

        String url = "jdbc:postgresql://127.0.0.1:" + started.getPort() + "/postgres";
        requirePassword(url, password.value(), hba);

        // The bundled server's own superuser runs the migrations, exactly as the database owner does on a
        // server. Every pooled connection then switches to solid_app, so row-level security still applies.
        return Map.of("spring.datasource.url", url,
                "spring.datasource.username", "postgres",
                "spring.datasource.password", password.value());
    }

    /** Written only while a new password is being set; replaced by {@link #ACCESS} straight afterwards. */
    private static final String ACCESS_WHILE_SETTING_PASSWORD = """
            # Written by Solid for a moment while it sets the database password. It is replaced within seconds.
            host all postgres 127.0.0.1/32 trust
            host all postgres ::1/128 trust
            """;

    /** Every connection, from anywhere, must prove it knows the password. No other way in. */
    private static final String ACCESS = """
            # Written by Solid (spec 065). Every connection needs the password in db.password, in the Solid folder.
            # Edits here are overwritten each time Solid starts.
            host all all 127.0.0.1/32 scram-sha-256
            host all all ::1/128 scram-sha-256
            """;

    /**
     * Sets the superuser's password and makes the server demand it. Idempotent, and done on every start, so an
     * install is brought up to this rule the first time it runs with it — and any hand edit is put back.
     */
    private void requirePassword(String url, String password, Path hba) throws IOException {
        try (java.sql.Connection db = java.sql.DriverManager.getConnection(url, "postgres", password);
                java.sql.Statement sql = db.createStatement()) {
            // Hex only, so there is nothing in it to quote; ALTER ROLE cannot take a bind parameter.
            if (!password.matches("[0-9a-f]+")) {
                throw new IllegalStateException("The database password file is not in the expected form");
            }
            sql.execute("alter role postgres password '" + password + "'");
            Files.writeString(hba, ACCESS, StandardCharsets.UTF_8);
            sql.execute("select pg_reload_conf()");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not secure the bundled database: " + e.getMessage(), e);
        }
    }

    /**
     * One copy at a time. Two servers on one data directory is how a database gets corrupted, so the second
     * one stops here with something a person can act on.
     */
    private void takeSingleInstanceLock(Path appDir) throws IOException {
        Path lockFile = appDir.resolve("solid.lock");
        try {
            FileLock taken = java.nio.channels.FileChannel
                    .open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                    .tryLock();
            if (taken == null) {
                throw alreadyRunning();
            }
            lock = taken;
        } catch (OverlappingFileLockException e) {
            throw alreadyRunning();
        }
    }

    private static IllegalStateException alreadyRunning() {
        return new IllegalStateException("Solid is already running on this computer. Open "
                + "http://127.0.0.1:" + PORT + " instead of starting it again.");
    }
}
