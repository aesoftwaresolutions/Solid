package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.Ids;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invitations: the way a second person gets an account once sign-up has closed.
 *
 * <p>The token is random, 32 bytes, and only its SHA-256 is stored — a stolen database hands over no usable
 * invitation, and the token is returned exactly once, when the invitation is made. Acceptance is the single
 * path past closed sign-up, and only for the address that was invited.
 */
@Service
public class InvitationService {

    static final Duration VALID_FOR = Duration.ofDays(7);

    public enum InviteStatus { pending, accepted, revoked, expired }

    /** @param token only ever set on the response to creating the invitation */
    public record Invitation(UUID id, UUID orgId, String email, Role role, InviteStatus status, UUID invitedBy,
                             OffsetDateTime createdAt, OffsetDateTime expiresAt, String token) {
    }

    /** @param created true when accepting made a new account, false when it only added a membership */
    public record Acceptance(UUID organizationId, String email, boolean created) {
    }

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final IamService iam;
    private final MembershipService memberships;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    InvitationService(JdbcClient db, TransactionTemplate tx, IamService iam, MembershipService memberships,
                      AuditLog audit) {
        this.db = db;
        this.tx = tx;
        this.iam = iam;
        this.memberships = memberships;
        this.audit = audit;
    }

    public Invitation invite(UUID orgId, String email, Role role, UUID invitedBy, String ip) {
        String normalized = normalize(email);
        return tx.execute(status -> {
            boolean alreadyMember = iam.findUserByEmail(normalized)
                    .map(user -> memberships.members(orgId).stream().anyMatch(m -> m.userId().equals(user.id())))
                    .orElse(false);
            if (alreadyMember) {
                throw new ApiProblemException(409, "ALREADY_MEMBER", "That person is already a member");
            }
            // Re-inviting replaces the old invitation, so nobody ends up holding two live tokens.
            db.sql("""
                    update iam.invitation set revoked_at = now()
                    where org_id = ? and email = ? and accepted_at is null and revoked_at is null""")
                    .params(orgId, normalized).update();

            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            UUID id = Ids.newId();
            OffsetDateTime expires = OffsetDateTime.now().plus(VALID_FOR);
            db.sql("""
                    insert into iam.invitation (id, org_id, email, role, token_hash, invited_by, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, orgId, normalized, role.name(), hash(token), invitedBy, expires).update();
            audit.record(new AuditLog.Actor(invitedBy, ip), orgId, "invitation_created", "invitation", id,
                    Map.of("email", normalized, "role", role.name()));
            return new Invitation(id, orgId, normalized, role, InviteStatus.pending, invitedBy,
                    OffsetDateTime.now(), expires, token);
        });
    }

    public List<Invitation> list(UUID orgId) {
        return db.sql("""
                select id, org_id, email, role, invited_by, created_at, expires_at, accepted_at, revoked_at
                from iam.invitation where org_id = ? order by created_at desc""")
                .param(orgId)
                .query((rs, n) -> new Invitation(
                        rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role")),
                        statusOf(rs.getObject("accepted_at", OffsetDateTime.class),
                                rs.getObject("revoked_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getObject("invited_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class), null))
                .list();
    }

    public void revoke(UUID orgId, UUID invitationId, UUID actorId, String ip) {
        int updated = db.sql("""
                update iam.invitation set revoked_at = now()
                where id = ? and org_id = ? and accepted_at is null and revoked_at is null""")
                .params(invitationId, orgId).update();
        if (updated == 0) {
            throw new NotFoundException("No invitation to revoke (it may already be accepted or revoked)");
        }
        audit.record(new AuditLog.Actor(actorId, ip), orgId, "invitation_revoked", "invitation", invitationId,
                Map.of());
    }

    /**
     * Turns a token into an account and a membership. This is the one way past closed sign-up, so everything
     * about the invitation is re-checked here rather than trusted from the link.
     */
    public Acceptance accept(String token, String displayName, String password, String ip) {
        return tx.execute(status -> {
            Map<String, Object> row = db.sql("""
                    select id, org_id, email, role, expires_at, accepted_at, revoked_at
                    from iam.invitation where token_hash = ? for update""")
                    .param(hash(token == null ? "" : token)).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> new ApiProblemException(404, "INVITATION_NOT_FOUND",
                            "That invitation link is not valid"));
            if (row.get("accepted_at") != null || row.get("revoked_at") != null) {
                throw new ApiProblemException(409, "INVITATION_USED",
                        "That invitation has already been used or was withdrawn");
            }
            // A generic row map gives whatever the driver returned (a java.sql.Timestamp here), so convert
            // rather than cast.
            Instant expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
            if (expires.isBefore(Instant.now())) {
                throw new ApiProblemException(409, "INVITATION_EXPIRED",
                        "That invitation has expired. Ask for a new one.");
            }
            UUID orgId = (UUID) row.get("org_id");
            UUID invitationId = (UUID) row.get("id");
            String email = (String) row.get("email");
            Role role = Role.valueOf((String) row.get("role"));

            Optional<IamService.User> existing = iam.findUserByEmail(email);
            IamService.User user = existing.orElseGet(() ->
                    // Only an invited address can get in this way, and only with its own new password: an
                    // existing account's password is never touched by a link someone was sent.
                    iam.createInvitedUser(email, password, displayName, ip));
            memberships.addMember(orgId, email, role);
            db.sql("update iam.invitation set accepted_at = now(), accepted_by = ? where id = ?")
                    .params(user.id(), invitationId).update();
            audit.record(new AuditLog.Actor(user.id(), ip), orgId, "invitation_accepted", "invitation",
                    invitationId, Map.of("email", email, "role", role.name(), "newAccount", existing.isEmpty()));
            return new Acceptance(orgId, email, existing.isEmpty());
        });
    }

    private static InviteStatus statusOf(OffsetDateTime accepted, OffsetDateTime revoked, OffsetDateTime expires) {
        if (accepted != null) {
            return InviteStatus.accepted;
        }
        if (revoked != null) {
            return InviteStatus.revoked;
        }
        return expires.toInstant().isBefore(Instant.now()) ? InviteStatus.expired : InviteStatus.pending;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** SHA-256 of the token. Lookup is by this hash, so no secret is ever compared byte by byte in Java. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
