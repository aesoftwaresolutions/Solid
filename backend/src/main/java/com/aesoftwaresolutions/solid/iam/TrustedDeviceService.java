package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.Ids;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A trusted device skips the authenticator code for a bounded time after proving the second factor once
 * (spec 068).
 *
 * <p>The record is a token, never a password-equivalent: the browser holds 32 random bytes, the database
 * holds only their SHA-256, and the row dies on its expiry date whether or not it is ever cleaned up. Unlike
 * a session it cannot do anything — it only marks one new session as having already passed the second
 * factor, on a machine that did so recently.
 */
@Service
public class TrustedDeviceService {

    static final java.time.Duration TRUSTED_FOR = java.time.Duration.ofDays(30);

    private final JdbcClient db;
    private final AuditLog audit;
    private final Clock clock;

    TrustedDeviceService(JdbcClient db, AuditLog audit, Clock clock) {
        this.db = db;
        this.audit = audit;
        this.clock = clock;
    }

    public record Device(UUID id, OffsetDateTime createdAt, OffsetDateTime lastUsedAt, OffsetDateTime expiresAt,
                         String ip, String userAgent) {
    }

    /**
     * Call only after the second factor has just been proven — this session or this activation. Returns the
     * raw token for the cookie; only its hash is stored.
     */
    public String trustThisDevice(SolidPrincipal principal, String ip, String userAgent) {
        String token = SessionTokens.newToken();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        db.sql("""
                insert into iam.trusted_device (id, user_id, token_hash, created_at, last_used_at, expires_at, ip, user_agent)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Ids.newId(), principal.userId(), SessionTokens.hash(token), now, now,
                        now.plus(TRUSTED_FOR), ip, truncate(userAgent)).update();
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "device_trusted", "user",
                principal.userId(), Map.of());
        return token;
    }

    /**
     * Answers whether the current (pending-MFA) session may skip the second factor because this machine
     * holds a live trusted-device token for this user — and if so, promotes that session, because that is the
     * whole point: the person's password was good and this device recently proved the second factor.
     */
    public boolean applyIfTrusted(SolidPrincipal principal, String rawToken) {
        if (principal.mfaVerified() || rawToken == null || rawToken.length() < 40 || rawToken.length() > 64) {
            return false;
        }
        List<UUID> matches = db.sql("""
                select id from iam.trusted_device
                where token_hash = ? and user_id = ? and expires_at > now()""")
                .params(SessionTokens.hash(rawToken), principal.userId()).query(UUID.class).list();
        if (matches.isEmpty()) {
            return false;
        }
        UUID deviceId = matches.get(0);
        db.sql("update iam.trusted_device set last_used_at = now() where id = ?").param(deviceId).update();
        db.sql("delete from iam.trusted_device where expires_at < now()").update();
        db.sql("update iam.session set mfa_verified = true where id = ?").param(principal.sessionId()).update();
        audit.record(new AuditLog.Actor(principal.userId(), ip(principal)), null, "mfa_trusted_device", "user",
                principal.userId(), Map.of());
        return true;
    }

    /** Every live device of this person, for their own list. */
    public List<Device> devices(UUID userId) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return db.sql("""
                select id, created_at, last_used_at, expires_at, ip, user_agent
                from iam.trusted_device where user_id = ? and expires_at > ?
                order by last_used_at desc""")
                .params(userId, now)
                .query((rs, n) -> new Device(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_used_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getString("ip"),
                        rs.getString("user_agent")))
                .list();
    }

    /** Forgets every trusted device of this person. */
    public int forgetAll(SolidPrincipal principal, String ip) {
        int forgotten = db.sql("delete from iam.trusted_device where user_id = ?")
                .param(principal.userId()).update();
        if (forgotten > 0) {
            audit.record(new AuditLog.Actor(principal.userId(), ip), null, "devices_forgotten", "user",
                    principal.userId(), Map.of("count", forgotten));
        }
        return forgotten;
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }

    private static String ip(SolidPrincipal principal) {
        return com.aesoftwaresolutions.solid.platform.RequestContext.current()
                .map(com.aesoftwaresolutions.solid.platform.RequestContext.Caller::ip).orElse(null);
    }
}
