package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.platform.FieldEncryptor;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-up, login, MFA and sessions.
 *
 * <p>Methods deliberately avoid one big transaction: a failed login must still record its failure count and
 * audit event even though the request ends in an error.
 */
@Service
public class IamService {

    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final int MAX_FAILED_MFA = 5;
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s]{2,}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    public record User(UUID id, String email, String displayName, boolean mfaEnabled, boolean isInstanceAdmin) {
    }

    public record LoginResult(String token, UUID sessionId, boolean mfaEnrolled) {
    }

    public record Enrollment(String secret, String otpauthUri) {
    }

    record Session(UUID id, UUID userId, String email, boolean mfaVerified, int failedMfaCount,
                   OffsetDateTime createdAt, OffsetDateTime lastSeenAt, OffsetDateTime expiresAt) {
    }

    private final JdbcClient db;
    private final AuditLog audit;
    private final FieldEncryptor encryptor;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean openSignup;
    private final Argon2PasswordEncoder passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** Hash used to keep login timing similar when the email doesn't exist. */
    private final String dummyHash;

    IamService(JdbcClient db, AuditLog audit, FieldEncryptor encryptor, TransactionTemplate tx, Clock clock,
               @Value("${solid.auth.open-signup:false}") boolean openSignup) {
        this.db = db;
        this.audit = audit;
        this.encryptor = encryptor;
        this.tx = tx;
        this.clock = clock;
        this.openSignup = openSignup;
        this.dummyHash = passwords.encode("not-a-real-password-" + UUID.randomUUID());
    }

    // ---------------- sign-up ----------------

    public User signup(String email, String password, String displayName, String ip) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        return tx.execute(status -> {
            db.sql("select pg_advisory_xact_lock(4242)").query().singleRow();
            long existingUsers = db.sql("select count(*) from iam.user_account").query(Long.class).single();
            if (existingUsers > 0 && !openSignup) {
                throw new ForbiddenException("Sign-up is closed. Ask an administrator to add you.");
            }
            boolean taken = db.sql("select exists (select 1 from iam.user_account where email = ?)")
                    .param(normalized).query(Boolean.class).single();
            if (taken) {
                throw new ApiProblemException(409, "EMAIL_TAKEN", "An account with this email already exists");
            }
            UUID id = Ids.newId();
            db.sql("""
                    insert into iam.user_account (id, email, display_name, password_hash, is_instance_admin)
                    values (?, ?, ?, ?, ?)""")
                    .params(id, normalized, displayName.trim(), passwords.encode(password), existingUsers == 0)
                    .update();
            audit.record(new AuditLog.Actor(id, ip), null, "signup", "user", id, Map.of("instanceAdmin", existingUsers == 0));
            return findUser(id).orElseThrow();
        });
    }

    /**
     * Creates an account for an address that was invited, bypassing the closed-sign-up check — that is what
     * an invitation is for. Everything else is the same as signing up, including the password rules, and the
     * new account is never an instance admin: only the very first account is.
     */
    public User createInvitedUser(String email, String password, String displayName, String ip) {
        String normalized = normalizeEmail(email);
        validatePassword(password);
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new ApiProblemException(400, "DISPLAY_NAME_REQUIRED", "A name is required");
        }
        boolean taken = db.sql("select exists (select 1 from iam.user_account where email = ?)")
                .param(normalized).query(Boolean.class).single();
        if (taken) {
            throw new ApiProblemException(409, "EMAIL_TAKEN", "An account with this email already exists");
        }
        UUID id = Ids.newId();
        db.sql("""
                insert into iam.user_account (id, email, display_name, password_hash, is_instance_admin)
                values (?, ?, ?, ?, false)""")
                .params(id, normalized, displayName.trim(), passwords.encode(password)).update();
        audit.record(new AuditLog.Actor(id, ip), null, "signup", "user", id, Map.of("invited", true));
        return findUser(id).orElseThrow();
    }

    // ---------------- login ----------------

    public LoginResult login(String email, String password, String ip, String userAgent) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> row = db.sql("""
                select id, password_hash, mfa_enabled, failed_login_count, locked_until
                from iam.user_account where email = ?""").param(normalized).query().listOfRows()
                .stream().findFirst().orElse(null);
        Instant now = clock.instant();

        if (row == null) {
            passwords.matches(password == null ? "" : password, dummyHash); // equalise timing
            audit.record(new AuditLog.Actor(null, ip), null, "login_failed", null, null, Map.of("reason", "unknown_email"));
            throw invalidCredentials();
        }
        UUID userId = (UUID) row.get("id");
        Instant lockedUntil = row.get("locked_until") instanceof java.sql.Timestamp ts ? ts.toInstant()
                : row.get("locked_until") instanceof OffsetDateTime odt ? odt.toInstant() : null;
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            audit.record(new AuditLog.Actor(userId, ip), null, "login_failed", "user", userId, Map.of("reason", "locked"));
            throw new ApiProblemException(423, "ACCOUNT_LOCKED", "Too many failed attempts. Try again later.");
        }
        if (password == null || !passwords.matches(password, (String) row.get("password_hash"))) {
            int failures = db.sql("""
                    update iam.user_account set failed_login_count = failed_login_count + 1 where id = ?
                    returning failed_login_count""").param(userId).query(Integer.class).single();
            audit.record(new AuditLog.Actor(userId, ip), null, "login_failed", "user", userId,
                    Map.of("reason", "bad_password", "consecutiveFailures", failures));
            if (failures >= MAX_FAILED_LOGINS) {
                db.sql("update iam.user_account set locked_until = ?, failed_login_count = 0 where id = ?")
                        .params(OffsetDateTime.ofInstant(now.plus(LOCKOUT), ZoneOffset.UTC), userId).update();
                audit.record(new AuditLog.Actor(userId, ip), null, "account_locked", "user", userId,
                        Map.of("minutes", LOCKOUT.toMinutes()));
            }
            throw invalidCredentials();
        }

        db.sql("update iam.user_account set failed_login_count = 0, locked_until = null where id = ?").param(userId).update();
        String token = SessionTokens.newToken();
        UUID sessionId = Ids.newId();
        db.sql("""
                insert into iam.session (id, token_hash, user_id, expires_at, last_seen_at, created_at, ip, user_agent)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(sessionId, SessionTokens.hash(token), userId,
                        OffsetDateTime.ofInstant(now.plus(ABSOLUTE_TIMEOUT), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(now, ZoneOffset.UTC), OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                        ip, truncate(userAgent, 300))
                .update();
        audit.record(new AuditLog.Actor(userId, ip), null, "login_succeeded", "user", userId, Map.of("mfaPending", true));
        return new LoginResult(token, sessionId, (Boolean) row.get("mfa_enabled"));
    }

    // ---------------- MFA ----------------

    public Enrollment enrollMfa(SolidPrincipal principal) {
        User user = findUser(principal.userId()).orElseThrow();
        if (user.mfaEnabled()) {
            throw new ApiProblemException(409, "MFA_ALREADY_ENABLED", "MFA is already set up for this account");
        }
        byte[] secret = Totp.newSecret();
        db.sql("update iam.user_account set mfa_secret_encrypted = ? where id = ? and not mfa_enabled")
                .params(encryptor.encrypt(Base32.encode(secret)), user.id()).update();
        return new Enrollment(Base32.encode(secret), Totp.otpauthUri("Solid", user.email(), secret));
    }

    public List<String> activateMfa(SolidPrincipal principal, String code, String ip) {
        User user = findUser(principal.userId()).orElseThrow();
        if (user.mfaEnabled()) {
            throw new ApiProblemException(409, "MFA_ALREADY_ENABLED", "MFA is already set up for this account");
        }
        String encrypted = db.sql("select mfa_secret_encrypted from iam.user_account where id = ?")
                .param(user.id()).query(String.class).optional()
                .orElseThrow(() -> new ApiProblemException(409, "MFA_NOT_ENROLLED", "Call /auth/mfa/enroll first"));
        long step = Totp.verify(Base32.decode(encryptor.decrypt(encrypted)), code, clock.instant(), null);
        if (step < 0) {
            recordMfaFailure(principal, ip);
            throw new ApiProblemException(401, "INVALID_MFA_CODE", "That code is not valid. Check your authenticator app's time.");
        }
        List<String> codes = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            db.sql("update iam.user_account set mfa_enabled = true, mfa_last_used_step = ? where id = ?")
                    .params(step, user.id()).update();
            db.sql("delete from iam.mfa_recovery_code where user_id = ?").param(user.id()).update();
            for (int i = 0; i < 10; i++) {
                String rc = recoveryCode();
                codes.add(rc);
                db.sql("insert into iam.mfa_recovery_code (id, user_id, code_hash) values (?, ?, ?)")
                        .params(Ids.newId(), user.id(), passwords.encode(rc)).update();
            }
            db.sql("update iam.session set mfa_verified = true, failed_mfa_count = 0 where id = ?")
                    .param(principal.sessionId()).update();
        });
        audit.record(new AuditLog.Actor(user.id(), ip), null, "mfa_enrolled", "user", user.id(), Map.of());
        return codes;
    }

    public void verifyMfa(SolidPrincipal principal, String code, String recoveryCode, String ip) {
        Map<String, Object> row = db.sql("select mfa_enabled, mfa_secret_encrypted, mfa_last_used_step from iam.user_account where id = ?")
                .param(principal.userId()).query().singleRow();
        if (!(Boolean) row.get("mfa_enabled")) {
            throw new ApiProblemException(409, "MFA_NOT_ENROLLED", "Set up MFA first");
        }
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            String normalized = recoveryCode.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> unused = db.sql("select id, code_hash from iam.mfa_recovery_code where user_id = ? and used_at is null")
                    .param(principal.userId()).query().listOfRows();
            for (Map<String, Object> rc : unused) {
                if (passwords.matches(normalized, (String) rc.get("code_hash"))) {
                    int claimed = db.sql("update iam.mfa_recovery_code set used_at = now() where id = ? and used_at is null")
                            .param(rc.get("id")).update();
                    if (claimed == 1) {
                        markVerified(principal);
                        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "recovery_code_used", "user",
                                principal.userId(), Map.of("remaining", unused.size() - 1));
                        return;
                    }
                }
            }
            recordMfaFailure(principal, ip);
            throw new ApiProblemException(401, "INVALID_MFA_CODE", "That recovery code is not valid");
        }

        byte[] secret = Base32.decode(encryptor.decrypt((String) row.get("mfa_secret_encrypted")));
        Long lastStep = (Long) row.get("mfa_last_used_step");
        long step = Totp.verify(secret, code, clock.instant(), lastStep);
        if (step < 0) {
            recordMfaFailure(principal, ip);
            throw new ApiProblemException(401, "INVALID_MFA_CODE", "That code is not valid or was already used");
        }
        int updated = db.sql("""
                update iam.user_account set mfa_last_used_step = ?
                where id = ? and (mfa_last_used_step is null or mfa_last_used_step < ?)""")
                .params(step, principal.userId(), step).update();
        if (updated == 0) { // concurrent replay
            recordMfaFailure(principal, ip);
            throw new ApiProblemException(401, "INVALID_MFA_CODE", "That code is not valid or was already used");
        }
        markVerified(principal);
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "mfa_verified", "user", principal.userId(), Map.of());
    }

    public void logout(SolidPrincipal principal, String ip) {
        db.sql("update iam.session set revoked_at = now() where id = ? and revoked_at is null").param(principal.sessionId()).update();
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "logout", "user", principal.userId(), Map.of());
    }

    // ---------------- sessions ----------------

    /** Resolves a token to an active session and refreshes its idle timer, or empty if invalid/expired. */
    Optional<SolidPrincipal> authenticate(String token, boolean bearer) {
        if (token == null || token.length() < 40 || token.length() > 64) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        OffsetDateTime idleCutoff = OffsetDateTime.ofInstant(now.minus(IDLE_TIMEOUT), ZoneOffset.UTC);
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return db.sql("""
                update iam.session s set last_seen_at = :now
                from iam.user_account u
                where s.token_hash = :hash and u.id = s.user_id
                  and s.revoked_at is null and s.expires_at > :now and s.last_seen_at > :idle
                returning s.id, s.user_id, u.email, s.mfa_verified, u.is_instance_admin""")
                .param("now", nowTs).param("hash", SessionTokens.hash(token)).param("idle", idleCutoff)
                .query((rs, n) -> new SolidPrincipal(rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("email"), rs.getBoolean("mfa_verified"), bearer,
                        rs.getBoolean("is_instance_admin")))
                .optional();
    }

    /** Everyone on this installation, for the instance administration screens (spec 048). */
    public List<User> allUsers() {
        return db.sql("select id, email, display_name, mfa_enabled, is_instance_admin from iam.user_account "
                + "order by email")
                .query(User.class).list();
    }

    public Optional<User> findUser(UUID id) {
        return db.sql("select id, email, display_name, mfa_enabled, is_instance_admin from iam.user_account where id = ?")
                .param(id).query(User.class).optional();
    }

    public Optional<User> findUserByEmail(String email) {
        return db.sql("select id, email, display_name, mfa_enabled, is_instance_admin from iam.user_account where email = ?")
                .param(email.trim().toLowerCase(Locale.ROOT)).query(User.class).optional();
    }

    // ---------------- helpers ----------------

    private void markVerified(SolidPrincipal principal) {
        db.sql("update iam.session set mfa_verified = true, failed_mfa_count = 0 where id = ?").param(principal.sessionId()).update();
    }

    private void recordMfaFailure(SolidPrincipal principal, String ip) {
        int failures = db.sql("update iam.session set failed_mfa_count = failed_mfa_count + 1 where id = ? returning failed_mfa_count")
                .param(principal.sessionId()).query(Integer.class).single();
        audit.record(new AuditLog.Actor(principal.userId(), ip), null, "mfa_failed", "user", principal.userId(),
                Map.of("consecutiveFailures", failures));
        if (failures >= MAX_FAILED_MFA) {
            db.sql("update iam.session set revoked_at = now() where id = ?").param(principal.sessionId()).update();
            throw new ApiProblemException(401, "SESSION_REVOKED", "Too many invalid codes. Please log in again.");
        }
    }

    private static ApiProblemException invalidCredentials() {
        return new ApiProblemException(401, "INVALID_CREDENTIALS", "Email or password is incorrect");
    }

    private static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches() || e.length() > 254) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        return e;
    }

    private static void validatePassword(String password) {
        validatePasswordRules(password);
    }

    /** The one place the password rules live, so changing and resetting cannot drift from signing up. */
    public static void validatePasswordRules(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 12 to 128 characters");
        }
    }

    private static String recoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
