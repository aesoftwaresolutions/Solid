package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.common.ForbiddenException;
import com.aesoftwaresolutions.solid.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Guards every {@code /api/v1/orgs/{orgId}/**} request: non-members get 404 (so org ids can't be probed) and
 * viewers can only read. Stores the caller's role in the request attribute {@link #ROLE_ATTRIBUTE}.
 */
@Component
class OrgAccessInterceptor implements HandlerInterceptor {

    static final String ROLE_ATTRIBUTE = "solid.orgRole";

    private final MembershipService memberships;

    OrgAccessInterceptor(MembershipService memberships) {
        this.memberships = memberships;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || !vars.containsKey("orgId")) {
            return true;
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(vars.get("orgId"));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Organization not found");
        }
        SolidPrincipal principal = CurrentUser.require();
        Role role = memberships.roleFor(orgId, principal.userId())
                .orElseThrow(() -> new NotFoundException("Organization " + orgId + " not found"));
        boolean safe = switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
        if (!safe && !role.canWrite()) {
            throw new ForbiddenException("Your role (" + role + ") is read-only in this organization");
        }
        request.setAttribute(ROLE_ATTRIBUTE, role);
        return true;
    }
}
