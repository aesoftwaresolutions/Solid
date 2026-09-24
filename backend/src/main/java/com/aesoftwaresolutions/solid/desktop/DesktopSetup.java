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
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
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

    private static final Logger log = LoggerFactory.getLogger(DesktopSetup.class);

    /** The port the desktop app answers on. Fixed, so the shortcut and the browser always agree. */
    public static final int PORT = 18080;

    private static EmbeddedPostgres postgres;
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
            log.info("Solid desktop: everything lives in {}", appDir);
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
    private static String masterKey(Path appDir) throws IOException {
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
        log.info("Solid desktop: made a new master key at {}. Back up this whole folder — without the key, "
                + "encrypted documents cannot be read.", keyFile);
        return encoded;
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows has no POSIX permissions; the file sits in the user's own profile directory, which is
            // the protection the platform offers. Nothing to do but carry on.
            log.debug("Could not set owner-only permissions on {}", file, e);
        }
    }

    /** Starts the bundled PostgreSQL against this installation's data directory and points Spring at it. */
    private static Map<String, Object> database(Path appDir) throws IOException {
        Path dataDir = appDir.resolve("db");
        stopAnyServerStillRunning(dataDir);
        EmbeddedPostgres started = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setOverrideWorkingDirectory(appDir.resolve("pgbin").toFile())
                // Loopback only: a desktop install is for the person at this machine.
                .setServerConfig("listen_addresses", "127.0.0.1")
                .start();
        postgres = started;
        Runtime.getRuntime().addShutdownHook(new Thread(DesktopSetup::stop, "solid-postgres-stop"));

        // The bundled server's own superuser runs the migrations, exactly as the database owner does on a
        // server. Every pooled connection then switches to solid_app, so row-level security still applies.
        String url = "jdbc:postgresql://127.0.0.1:" + started.getPort() + "/postgres";
        return Map.of("spring.datasource.url", url,
                "spring.datasource.username", "postgres",
                "spring.datasource.password", "postgres");
    }

    /**
     * Takes over a data directory that a previous copy is still using.
     *
     * <p>A copy killed outright — task manager, a power cut, an installer replacing the files — leaves its
     * PostgreSQL running, and that server keeps the data directory locked so the next start fails. The pid
     * file names it, so it can be stopped first. Solid is deliberately careful here: a pid file whose process
     * is long gone must be ignored rather than acted on, because that number may belong to something else
     * entirely by now (spec 063).
     */
    static void stopAnyServerStillRunning(Path dataDir) {
        Path pidFile = dataDir.resolve("postmaster.pid");
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            // The first line of postmaster.pid is the postmaster's process id; the rest is its own business.
            String first = Files.readAllLines(pidFile).stream().findFirst().orElse("").trim();
            long pid = Long.parseLong(first);
            Optional<ProcessHandle> running = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (running.isEmpty()) {
                log.debug("The data directory names process {}, which is gone; carrying on.", pid);
                return;
            }
            // Only stop it if it really is a PostgreSQL of ours: the number alone is not proof.
            String command = running.get().info().command().orElse("");
            if (!command.toLowerCase(Locale.ROOT).contains("postgres")) {
                log.warn("The data directory names process {} ({}), which is not a database; leaving it be.",
                        pid, command);
                return;
            }
            log.info("A database from an earlier run of Solid is still going (process {}); stopping it.", pid);
            running.get().destroy();
            if (!running.get().onExit().orTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .handle((handle, error) -> error == null).join()) {
                running.get().destroyForcibly();
            }
        } catch (NumberFormatException | IOException e) {
            log.debug("Could not read {}; letting PostgreSQL decide what to do about it.", pidFile, e);
        }
    }

    private static void stop() {
        EmbeddedPostgres running = postgres;
        postgres = null;
        if (running == null) {
            return;
        }
        try {
            // close() runs pg_ctl stop and waits for it. Anything left behind would hold the installed files
            // open and block the next upgrade, which is exactly what happened before spec 063.
            running.close();
            log.info("The bundled database has stopped.");
        } catch (IOException e) {
            log.warn("The bundled database did not stop cleanly; the next start will take it over.", e);
        }
    }

    /**
     * One copy at a time. Two servers on one data directory is how a database gets corrupted, so the second
     * one stops here with something a person can act on.
     */
    private static void takeSingleInstanceLock(Path appDir) throws IOException {
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
