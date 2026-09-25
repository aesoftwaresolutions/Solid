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
    private final InvitationService invitations;
    private final AuditLog audit;

    MemberController(MembershipService memberships, InvitationService invitations, AuditLog audit) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.audit = audit;
    }

    /**
     * Everyone who works on the books may see who else does. A viewer — often the client whose books they are —
     * may not: the list is every colleague's email address (spec 065, row 8).
     */
    @GetMapping("/members")
    List<MembershipService.Member> list(@PathVariable UUID orgId, HttpServletRequest request) {
        Role role = (Role) request.getAttribute(OrgAccessInterceptor.ROLE_ATTRIBUTE);
        if (role == null || !role.canWrite()) {
            throw new ForbiddenException("Viewers can see the books, but not the list of people");
        }
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

    record ChangeRole(@NotNull Role role) {
    }

    record InviteRequest(@NotBlank @jakarta.validation.constraints.Email String email, @NotNull Role role) {
    }

    /** The token is in this response and nowhere else — there is no way to read it again. */
    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationService.Invitation invite(@PathVariable UUID orgId, @Valid @RequestBody InviteRequest body,
                                        HttpServletRequest request) {
        requireManager(request);
        if (body.role() == Role.owner && request.getAttribute(OrgAccessInterceptor.ROLE_ATTRIBUTE) != Role.owner) {
            throw new ForbiddenException("Only owners can invite owners");
        }
        return invitations.invite(orgId, body.email(), body.role(), CurrentUser.require().userId(),
                ClientIp.of(request));
    }

    @GetMapping("/invitations")
    List<InvitationService.Invitation> listInvitations(@PathVariable UUID orgId, HttpServletRequest request) {
        requireManager(request);
        return invitations.list(orgId);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeInvitation(@PathVariable UUID orgId, @PathVariable UUID invitationId, HttpServletRequest request) {
        requireManager(request);
        invitations.revoke(orgId, invitationId, CurrentUser.require().userId(), ClientIp.of(request));
    }

    @org.springframework.web.bind.annotation.PatchMapping("/members/{userId}")
    MembershipService.Member changeRole(@PathVariable UUID orgId, @PathVariable UUID userId,
                                        @Valid @RequestBody ChangeRole body, HttpServletRequest request) {
        requireManager(request);
        requireOwnerForOwners(request, orgId, userId, body.role());
        return memberships.changeRole(orgId, userId, body.role(), CurrentUser.require().userId(),
                ClientIp.of(request));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable UUID orgId, @PathVariable UUID userId, HttpServletRequest request) {
        requireManager(request);
        requireOwnerForOwners(request, orgId, userId, null);
        memberships.removeMember(orgId, userId, CurrentUser.require().userId(), ClientIp.of(request));
    }

    /**
     * Owners are the one thing an admin may not touch: an admin who could demote an owner would effectively
     * be an owner. Granting the owner role is owners-only for the same reason.
     */
    private void requireOwnerForOwners(HttpServletRequest request, UUID orgId, UUID userId, Role newRole) {
        if (request.getAttribute(OrgAccessInterceptor.ROLE_ATTRIBUTE) == Role.owner) {
            return;
        }
        if (newRole == Role.owner) {
            throw new ForbiddenException("Only owners can make someone an owner");
        }
        if (memberships.roleFor(orgId, userId).orElse(null) == Role.owner) {
            throw new ForbiddenException("Only owners can change or remove another owner");
        }
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
