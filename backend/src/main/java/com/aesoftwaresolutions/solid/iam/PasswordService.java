package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Changing a password, and getting back in after forgetting one.
 *
 * <p>A reset token is random, stored only as its SHA-256, good for one hour and one use. Using it sets the
 * password and revokes every session that user had — including any a thief was holding. It does not sign
 * anyone in and does not touch MFA: recovery codes are what a lost phone is for.
 */
@Service
public class PasswordService {

    static final Duration RESET_VALID_FOR = Duration.ofHours(1);

    public record Reset(UUID userId, String email, OffsetDateTime expiresAt, String token) {
    }

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final IamService iam;
    private final AuditLog audit;
    private final Argon2PasswordEncoder passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final SecureRandom random = new SecureRandom();

    PasswordService(JdbcClient db, TransactionTemplate tx, IamService iam, AuditLog audit) {
        this.db = db;
        this.tx = tx;
        this.iam = iam;
        this.audit = audit;
    }

    /** Changes your own password. Other sessions go; the one you are using stays. */
    public void change(SolidPrincipal principal, String currentPassword, String newPassword, String ip) {
        String hash = db.sql("select password_hash from iam.user_account where id = ?")
                .param(principal.userId()).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("No such user"));
        if (currentPassword == null || !passwords.matches(currentPassword, hash)) {
            audit.record(new AuditLog.Actor(principal.userId(), ip), null, "password_change_failed", "user",
                    principal.userId(), Map.of());
            throw new ApiProblemException(401, "WRONG_PASSWORD", "That is not your current password");
        }
        if (passwords.matches(newPassword == null ? "" : newPassword, hash)) {
            throw new ApiProblemException(400, "PASSWORD_UNCHANGED", "The new password is the old one");
        }
        IamService.validatePasswordRules(newPassword);
        // One transaction: a new password with the old sessions still live is exactly the state someone
        // changing their password because of a break-in must never end up in.
        tx.executeWithoutResult(status -> {
            db.sql("update iam.user_account set password_hash = ? where id = ?")
                    .params(passwords.encode(newPassword), principal.userId()).update();
            db.sql("update iam.session set revoked_at = now() where user_id = ? and id <> ? and revoked_at is null")
                    .params(principal.userId(), principal.sessionId()).update();
        });
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "password_changed", "user",
                principal.userId(), Map.of());
    }

    /**
     * Issues a one-time link. {@code issuedBy} is null when it came from the command line, which is the way
     * back in when the last administrator is the one locked out.
     */
    public Reset issueReset(UUID userId, UUID issuedBy, String ip) {
        IamService.User user = iam.findUser(userId)
                .orElseThrow(() -> new NotFoundException("No user with that id"));
        return tx.execute(status -> {
            // A newer reset replaces any older one, so only the most recent link works.
            db.sql("update iam.password_reset set used_at = now() where user_id = ? and used_at is null")
                    .param(userId).update();
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            OffsetDateTime expires = OffsetDateTime.now().plus(RESET_VALID_FOR);
            db.sql("""
                    insert into iam.password_reset (id, user_id, token_hash, issued_by, expires_at)
                    values (?, ?, ?, ?, ?)""")
                    .params(Ids.newId(), userId, InvitationService.hash(token), issuedBy, expires).update();
            audit.record(new AuditLog.Actor(issuedBy, ip), null, "password_reset_issued", "user", userId,
                    Map.of("issuedFrom", issuedBy == null ? "command line" : "instance admin"));
            return new Reset(userId, user.email(), expires, token);
        });
    }

    /** Spends a token and sets the password. Every session that user had is revoked. */
    public void useReset(String token, String newPassword, String ip) {
        tx.executeWithoutResult(status -> {
            Map<String, Object> row = db.sql("""
                    select id, user_id, expires_at, used_at from iam.password_reset
                    where token_hash = ? for update""")
                    .param(InvitationService.hash(token == null ? "" : token))
                    .query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> new ApiProblemException(404, "RESET_NOT_FOUND",
                            "That reset link is not valid"));
            if (row.get("used_at") != null) {
                throw new ApiProblemException(409, "RESET_USED", "That reset link has already been used");
            }
            if (Timestamps.toInstant(row.get("expires_at")).isBefore(Instant.now())) {
                throw new ApiProblemException(409, "RESET_EXPIRED", "That reset link has expired");
            }
            UUID userId = (UUID) row.get("user_id");
            IamService.validatePasswordRules(newPassword);
            db.sql("update iam.user_account set password_hash = ?, failed_login_count = 0, locked_until = null "
                    + "where id = ?")
                    .params(passwords.encode(newPassword), userId).update();
            db.sql("update iam.password_reset set used_at = now() where id = ?").param(row.get("id")).update();
            db.sql("update iam.session set revoked_at = now() where user_id = ? and revoked_at is null")
                    .param(userId).update();
            audit.record(new AuditLog.Actor(userId, ip), null, "password_reset_used", "user", userId, Map.of());
        });
    }
}
