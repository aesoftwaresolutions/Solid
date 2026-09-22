package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Your own sessions: where you are signed in, and how to stop being.
 *
 * <p>The rows have existed since sessions did; this only shows and revokes them. What it shows is what was
 * recorded at sign-in — an address and a browser string — and nothing else. Solid does not know where you
 * were sitting, and a "location" column would be theatre.
 */
@Service
public class SessionService {

    /** @param current true for the session making this request */
    public record SessionInfo(UUID id, OffsetDateTime createdAt, OffsetDateTime lastSeenAt,
                              OffsetDateTime expiresAt, String ip, String userAgent, boolean mfaVerified,
                              boolean current) {
    }

    private final JdbcClient db;
    private final AuditLog audit;
    private final Clock clock;

    SessionService(JdbcClient db, AuditLog audit, Clock clock) {
        this.db = db;
        this.audit = audit;
        this.clock = clock;
    }

    /** The caller's live sessions. A revoked, expired or idled-out session is already dead, so it is left out. */
    public List<SessionInfo> list(SolidPrincipal principal) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime idleCutoff = now.minus(IamService.IDLE_TIMEOUT);
        return db.sql("""
                select id, created_at, last_seen_at, expires_at, ip, user_agent, mfa_verified
                from iam.session
                where user_id = ? and revoked_at is null and expires_at > ? and last_seen_at > ?
                order by last_seen_at desc""")
                .params(principal.userId(), now, idleCutoff)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new SessionInfo(id, rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("last_seen_at", OffsetDateTime.class),
                            rs.getObject("expires_at", OffsetDateTime.class), rs.getString("ip"),
                            rs.getString("user_agent"), rs.getBoolean("mfa_verified"),
                            id.equals(principal.sessionId()));
                })
                .list();
    }

    /**
     * Ends one of your own sessions. Someone else's id is a 404 rather than a 403, so the ids of sessions
     * you cannot touch cannot be probed either.
     */
    public void revoke(SolidPrincipal principal, UUID sessionId, String ip) {
        int revoked = db.sql("""
                update iam.session set revoked_at = now()
                where id = ? and user_id = ? and revoked_at is null""")
                .params(sessionId, principal.userId()).update();
        if (revoked == 0) {
            throw new NotFoundException("No live session of yours with that id");
        }
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "session_revoked", "session", sessionId,
                Map.of("count", 1, "self", sessionId.equals(principal.sessionId())));
    }

    /** Ends every session except this one. */
    public int revokeOthers(SolidPrincipal principal, String ip) {
        int revoked = db.sql("""
                update iam.session set revoked_at = now()
                where user_id = ? and id <> ? and revoked_at is null""")
                .params(principal.userId(), principal.sessionId()).update();
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "session_revoked", "user",
                principal.userId(), Map.of("count", revoked, "self", false));
        return revoked;
    }
}
