package com.aesoftwaresolutions.solid.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Where to reach a PostgreSQL server that is not the one Solid bundles: spec 060 assumed every desktop install
 * runs its own local database, but some setups already have a PostgreSQL server elsewhere (a NAS, a small
 * office server, a machine ops already trusts) and want the desktop app to use that instead.
 *
 * <p>Kept in one file, owner-only like every other secret in the Solid folder. Its mere presence, with
 * {@code enabled=true}, is what tells {@link DesktopSetup} to skip starting the bundled server altogether —
 * row-level security still applies, because it lives in the database itself, not in which server hosts it.
 */
record RemoteDatabaseConfig(String host, int port, String database, String username, String password,
        SslMode sslMode) {

    enum SslMode {
        DISABLE, REQUIRE, VERIFY_FULL;

        @com.fasterxml.jackson.annotation.JsonValue
        String jdbcValue() {
            return switch (this) {
                case DISABLE -> "disable";
                case REQUIRE -> "require";
                case VERIFY_FULL -> "verify-full";
            };
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        static SslMode parse(String value) {
            if (value == null || value.isBlank()) {
                return REQUIRE;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "disable" -> DISABLE;
                case "verify-full", "verify_full" -> VERIFY_FULL;
                default -> REQUIRE;
            };
        }
    }

    static final String FILE_NAME = "remote-db.properties";

    String jdbcUrl() {
        // A short connect timeout: a wrong host or a closed firewall port should fail in seconds, not hang the
        // settings screen (or, at startup, hang the whole app) on the OS's own TCP timeout. The socket timeout
        // is its pair: a host that answers TCP but then stalls — a TLS handshake blackhole under sslmode=require,
        // a firewall that drops mid-flight — bounds here instead of hanging forever.
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=" + sslMode.jdbcValue()
                + "&connectTimeout=5&socketTimeout=10";
    }

    /** Never put the real password in a response; this is what the settings screen gets back instead. */
    RemoteDatabaseConfig masked() {
        return new RemoteDatabaseConfig(host, port, database, username, password.isEmpty() ? "" : "••••••••", sslMode);
    }

    void save(Path appDir) throws IOException {
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("host", host);
        props.setProperty("port", Integer.toString(port));
        props.setProperty("database", database);
        props.setProperty("username", username);
        props.setProperty("password", password);
        props.setProperty("sslmode", sslMode.jdbcValue());
        Path file = appDir.resolve(FILE_NAME);
        Path tmp = appDir.resolve(FILE_NAME + ".tmp");
        try (var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            props.store(out, "Written by Solid. The password here is exactly as sensitive as db.password.");
        }
        if (!DesktopSetup.ownerOnlyStatic(tmp)) {
            // A plaintext database password must never sit on disk with default permissions; refuse instead.
            Files.deleteIfExists(tmp);
            throw new IOException("Could not restrict " + FILE_NAME + " to this user; refusing to save");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    static void delete(Path appDir) throws IOException {
        Files.deleteIfExists(appDir.resolve(FILE_NAME));
    }

    static boolean exists(Path appDir) {
        return Files.exists(appDir.resolve(FILE_NAME));
    }

    /** Empty when there is no file, or it says {@code enabled=false} — either way, use the bundled database. */
    static Optional<RemoteDatabaseConfig> load(Path appDir) throws IOException {
        Path file = appDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        if (!"true".equalsIgnoreCase(props.getProperty("enabled", "false"))) {
            return Optional.empty();
        }
        String portValue = props.getProperty("port", "5432");
        int port;
        try {
            port = Integer.parseInt(portValue.trim());
        } catch (NumberFormatException e) {
            throw new IOException("The saved database port (" + portValue + ") is not a number");
        }
        return Optional.of(new RemoteDatabaseConfig(
                props.getProperty("host", ""),
                port,
                props.getProperty("database", ""),
                props.getProperty("username", ""),
                props.getProperty("password", ""),
                SslMode.parse(props.getProperty("sslmode"))));
    }

    /** A quick, human check before anything is saved or connected to for real. */
    void requireSane() {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A host is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("The port must be between 1 and 65535");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("A database name is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("A username is required");
        }
    }
}
