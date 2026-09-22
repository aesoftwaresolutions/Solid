package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Who belongs to which organization, with which role. Public API used by other modules. */
@Service
public class MembershipService {

    public record Member(UUID userId, String email, String displayName, Role role) {
    }

    private final JdbcClient db;
    private final AuditLog audit;
    private final IamService iam;

    MembershipService(JdbcClient db, AuditLog audit, IamService iam) {
        this.db = db;
        this.audit = audit;
        this.iam = iam;
    }

    public void addOwner(UUID orgId, UUID userId) {
        db.sql("insert into iam.membership (org_id, user_id, role) values (?, ?, 'owner')").params(orgId, userId).update();
    }

    public Optional<Role> roleFor(UUID orgId, UUID userId) {
        return db.sql("select role from iam.membership where org_id = ? and user_id = ?")
                .params(orgId, userId).query(String.class).optional().map(Role::valueOf);
    }

    public List<UUID> orgIdsFor(UUID userId) {
        return db.sql("select org_id from iam.membership where user_id = ? order by created_at")
                .param(userId).query(UUID.class).list();
    }

    public List<Member> members(UUID orgId) {
        return db.sql("""
                select u.id as user_id, u.email, u.display_name, m.role
                from iam.membership m join iam.user_account u on u.id = m.user_id
                where m.org_id = ? order by m.created_at""")
                .param(orgId)
                .query((rs, n) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), Role.valueOf(rs.getString("role"))))
                .list();
    }

    /**
     * Changes what someone may do here. An organization must always keep at least one owner: a set of books
     * nobody can administer is worse than one with too many administrators.
     */
    public Member changeRole(UUID orgId, UUID userId, Role role, UUID actorId, String ip) {
        Role current = roleFor(orgId, userId)
                .orElseThrow(() -> new NotFoundException("That person is not a member of this organization"));
        if (current == Role.owner && role != Role.owner) {
            requireAnotherOwner(orgId, userId, "demote");
        }
        db.sql("update iam.membership set role = ? where org_id = ? and user_id = ?")
                .params(role.name(), orgId, userId).update();
        audit.record(new AuditLog.Actor(actorId, ip), orgId, "member_role_changed", "user", userId,
                Map.of("from", current.name(), "to", role.name()));
        return members(orgId).stream().filter(m -> m.userId().equals(userId)).findFirst().orElseThrow();
    }

    /**
     * Ends someone's access to this organization. Their history stays: the books record what happened, not
     * who currently has a login.
     */
    public void removeMember(UUID orgId, UUID userId, UUID actorId, String ip) {
        Role current = roleFor(orgId, userId)
                .orElseThrow(() -> new NotFoundException("That person is not a member of this organization"));
        if (current == Role.owner) {
            requireAnotherOwner(orgId, userId, "remove");
        }
        db.sql("delete from iam.membership where org_id = ? and user_id = ?").params(orgId, userId).update();
        audit.record(new AuditLog.Actor(actorId, ip), orgId, "member_removed", "user", userId,
                Map.of("role", current.name()));
    }

    private void requireAnotherOwner(UUID orgId, UUID userId, String verb) {
        Integer otherOwners = db.sql(
                "select count(*) from iam.membership where org_id = ? and role = 'owner' and user_id <> ?")
                .params(orgId, userId).query(Integer.class).single();
        if (otherOwners == 0) {
            throw new ApiProblemException(409, "LAST_OWNER",
                    "You cannot " + verb + " the last owner: someone has to be able to administer these books. "
                            + "Make someone else an owner first.");
        }
    }

    public Member addMember(UUID orgId, String email, Role role) {
        IamService.User user = iam.findUserByEmail(email)
                .orElseThrow(() -> new NotFoundException("No user with that email. They need an account first."));
        int inserted = db.sql("insert into iam.membership (org_id, user_id, role) values (?, ?, ?) on conflict do nothing")
                .params(orgId, user.id(), role.name()).update();
        if (inserted == 0) {
            throw new ApiProblemException(409, "ALREADY_MEMBER", "That user is already a member");
        }
        audit.record(AuditLog.Actor.current(), orgId, "member_added", "user", user.id(), Map.of("role", role.name()));
        return new Member(user.id(), user.email(), user.displayName(), role);
    }
}
