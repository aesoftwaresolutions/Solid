package com.aesoftwaresolutions.solid.platform;

import com.aesoftwaresolutions.solid.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Remembers which installation this database belongs to, and which master key it was encrypted with.
 *
 * <p>MFA secrets and every uploaded document are encrypted with {@code SOLID_MASTER_KEY}. A database restored on a
 * server holding a <em>different</em> key would start happily and only fail later, one unreadable record at a time.
 * So the first startup stores a one-way fingerprint of the key, and every later startup compares against it.
 */
@Component
public class InstanceIdentity {

    /** Mixed into the hash so the stored value cannot be compared against other systems' key hashes. */
    private static final String DOMAIN = "solid-instance-key-fingerprint";

    private final JdbcClient db;
    private final String fingerprint;
    private volatile UUID instanceId;

    InstanceIdentity(JdbcClient db, @Value("${solid.security.master-key}") String base64Key) {
        this.db = db;
        this.fingerprint = fingerprintOf(base64Key);
    }

    /** SHA-256 over a fixed label and the raw key. One-way: it identifies a key without revealing it. */
    public static String fingerprintOf(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
            digest.update(raw);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs while the context is still starting — before the HTTP port is open — so a server holding the wrong key
     * never accepts an upload it would encrypt unreadably.
     */
    @jakarta.annotation.PostConstruct
    void verifyOnStartup() {
        verify();
    }

    /** Stores the fingerprint on a fresh database; refuses to run if the key has changed since. */
    public synchronized void verify() {
        Optional<UUID> existingId = db.sql("select id from sys.instance").query(UUID.class).optional();
        if (existingId.isEmpty()) {
            UUID id = Ids.newId();
            db.sql("insert into sys.instance (id, master_key_fingerprint) values (?, ?)")
                    .params(id, fingerprint).update();
            this.instanceId = id;
            return;
        }
        String stored = db.sql("select master_key_fingerprint from sys.instance").query(String.class).single().trim();
        if (!stored.equals(fingerprint)) {
            throw new IllegalStateException("""
                    This database was encrypted with a different SOLID_MASTER_KEY than the one this server is \
                    running with. Starting anyway would leave MFA secrets and uploaded documents unreadable. This \
                    usually means a backup was restored next to the wrong key, or the key was changed by hand. \
                    Put the original key back, or see docs/operations.md.""");
        }
        this.instanceId = existingId.get();
    }

    public UUID instanceId() {
        if (instanceId == null) {
            verify();
        }
        return instanceId;
    }

    /** The full fingerprint. Show {@link #shortFingerprint()} to people instead. */
    public String fingerprint() {
        return fingerprint;
    }

    public String shortFingerprint() {
        return fingerprint.substring(0, 16);
    }
}
