package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.audit.AuditEvent;
import com.aesoftwaresolutions.solid.audit.AuditLog;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orgs/{orgId}")
class MemberController {

    record AddMember(@NotBlank String email, @NotNull Role role) {
    }

    private final MembershipService memberships;
    private final AuditLog audit;

    MemberController(MembershipService memberships, AuditLog audit) {
        this.memberships = memberships;
        this.audit = audit;
    }

    @GetMapping("/members")
    List<MembershipService.Member> list(@PathVariable UUID orgId) {
        return memberships.members(orgId);
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    MembershipService.Member add(@PathVariable UUID orgId, @Valid @RequestBody AddMember body, HttpServletRequest request) {
        requireManager(request);
        if (body.role() == Role.owner && request.getAttribute(OrgAccessInterceptor.ROLE_ATTRIBUTE) != Role.owner) {
            throw new ForbiddenException("Only owners can add owners");
        }
        return memberships.addMember(orgId, body.email(), body.role());
    }

    @GetMapping("/audit-events")
    List<AuditEvent> auditEvents(@PathVariable UUID orgId, @RequestParam(defaultValue = "100") int limit,
                                 HttpServletRequest request) {
        requireManager(request);
        return audit.forOrg(orgId, limit);
    }

    private static void requireManager(HttpServletRequest request) {
        Role role = (Role) request.getAttribute(OrgAccessInterceptor.ROLE_ATTRIBUTE);
        if (role == null || !role.canManageMembers()) {
            throw new ForbiddenException("Only owners and admins can do this");
        }
    }
}
