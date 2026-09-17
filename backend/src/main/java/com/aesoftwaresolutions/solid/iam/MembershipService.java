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
