package com.aesoftwaresolutions.solid.desktop;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings, for the desktop build only: point Solid at a PostgreSQL server other than the one it bundles
 * (spec 066). Absent entirely from a server install — there, this is the operator's environment variables,
 * as it always was.
 *
 * <p>Saving takes effect on the next start, not this one: the connection pool and Flyway are already wired
 * to whatever {@link DesktopSetup} decided at boot, and re-pointing them live is not worth the risk of doing
 * it wrong under a customer's books. The settings screen says so and offers to close Solid for the person to
 * reopen.
 */
@RestController
@RequestMapping("/api/v1/desktop/database")
@Profile("desktop")
class DesktopDatabaseController {

    private final Environment environment;

    DesktopDatabaseController(Environment environment) {
        this.environment = environment;
    }

    record Status(String mode, RemoteDatabaseConfig current) {
    }

    record ConnectionInput(String host, Integer port, String database, String username, String password,
            String sslMode) {

        RemoteDatabaseConfig toConfig() {
            return new RemoteDatabaseConfig(host, port == null ? 5432 : port, database, username,
                    password == null ? "" : password, RemoteDatabaseConfig.SslMode.parse(sslMode));
        }
    }

    @GetMapping
    Status status() {
        requireInstanceAdmin();
        Path appDir = DesktopSetup.appDir(environment);
        try {
            java.util.Optional<RemoteDatabaseConfig> remote = RemoteDatabaseConfig.load(appDir);
            return remote.map(config -> new Status("remote", config.masked()))
                    .orElseGet(() -> new Status("bundled", null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Tries the connection without saving anything, so a typo is caught before it locks anyone out. */
    @PostMapping("/test")
    Map<String, Object> test(@RequestBody ConnectionInput input) {
        requireInstanceAdmin();
        RemoteDatabaseConfig config = input.toConfig();
        try {
            config.requireSane();
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
        return connect(config);
    }

    @PutMapping
    Map<String, Object> save(@RequestBody ConnectionInput input) {
        requireInstanceAdmin();
        RemoteDatabaseConfig config = input.toConfig();
        config.requireSane();
        Map<String, Object> result = connect(config);
        if (!(Boolean) result.get("ok")) {
            throw new ApiProblemException(409, "CANNOT_CONNECT", (String) result.get("message"));
        }
        try {
            config.save(DesktopSetup.appDir(environment));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("saved", true, "restartRequired", true);
    }

    /** Back to the database Solid bundles. Takes effect on the next start, same as saving one does. */
    @DeleteMapping
    Map<String, Object> reset() {
        requireInstanceAdmin();
        try {
            RemoteDatabaseConfig.delete(DesktopSetup.appDir(environment));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("saved", true, "restartRequired", true);
    }

    private Map<String, Object> connect(RemoteDatabaseConfig config) {
        try (var ignored = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password())) {
            return Map.of("ok", true);
        } catch (SQLException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private static void requireInstanceAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiProblemException(401, "UNAUTHENTICATED", "Please log in");
        }
        boolean admin = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("INSTANCE_ADMIN"::equals);
        if (!admin) {
            throw new ForbiddenException("Only an instance administrator can do this");
        }
    }
}
