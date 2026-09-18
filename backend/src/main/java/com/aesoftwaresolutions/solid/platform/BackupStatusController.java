package com.aesoftwaresolutions.solid.platform;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of what a backup of this instance would contain. Instance administrators only. */
@RestController
@RequestMapping("/api/v1/system")
class BackupStatusController {

    /** The tables worth eyeballing after a restore: the books, the paperwork and the audit trail. */
    private static final List<String> WATCHED = List.of("org.organization", "org.entity", "gl.journal_entry",
            "gl.journal_line", "bank.bank_txn", "ar_ap.invoice", "ar_ap.bill", "doc.document", "audit.event");

    private final JdbcClient db;
    private final InstanceIdentity instance;
    private final Flyway flyway;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Clock clock;
    private final Path documentsRoot;

    BackupStatusController(JdbcClient db, InstanceIdentity instance, Flyway flyway,
                           ObjectProvider<BuildProperties> buildProperties, Clock clock,
                           @Value("${solid.documents.root:./data/documents}") String documentsRoot) {
        this.db = db;
        this.instance = instance;
        this.flyway = flyway;
        this.buildProperties = buildProperties;
        this.clock = clock;
        this.documentsRoot = Path.of(documentsRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/backup-status")
    BackupStatus status() {
        requireInstanceAdmin();
        MigrationInfo current = flyway.info().current();
        BuildProperties build = buildProperties.getIfAvailable();

        long databaseBytes = db.sql("select pg_database_size(current_database())").query(Long.class).single();
        Map<String, Long> estimates = new LinkedHashMap<>();
        for (String table : WATCHED) {
            String[] parts = table.split("\\.");
            estimates.put(table, db.sql("""
                    select coalesce(max(n_live_tup), 0) from pg_stat_user_tables
                    where schemaname = ? and relname = ?""")
                    .params(parts[0], parts[1]).query(Long.class).single());
        }

        return new BackupStatus(instance.instanceId(), instance.shortFingerprint(),
                current != null ? current.getVersion().getVersion() : "none",
                build != null ? build.getVersion() : "dev",
                databaseBytes, estimates, documents(), documentsRoot.toString(),
                OffsetDateTime.now(clock));
    }

    /** Walks the document vault. The files are ciphertext, so only their number and size are read. */
    private BackupStatus.Documents documents() {
        if (!Files.isDirectory(documentsRoot)) {
            return new BackupStatus.Documents(0, 0);
        }
        try (Stream<Path> paths = Files.walk(documentsRoot)) {
            long[] totals = paths.filter(Files::isRegularFile).mapToLong(BackupStatusController::sizeOf)
                    .collect(() -> new long[2], (acc, size) -> {
                        acc[0]++;
                        acc[1] += size;
                    }, (a, b) -> {
                        a[0] += b[0];
                        a[1] += b[1];
                    });
            return new BackupStatus.Documents(totals[0], totals[1]);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the document vault", e);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
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
            throw new ForbiddenException("Only an instance administrator can see the backup status");
        }
    }
}
