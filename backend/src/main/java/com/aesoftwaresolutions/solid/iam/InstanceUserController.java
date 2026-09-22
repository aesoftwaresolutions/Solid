package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import com.aesoftwaresolutions.solid.common.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people on this installation, for whoever administers it. Sign-up is closed and there is no email, so
 * handing someone a reset link is the administrator's job (spec 048).
 */
@RestController
@RequestMapping("/api/v1/instance")
class InstanceUserController {

    private final IamService iam;
    private final PasswordService passwords;

    InstanceUserController(IamService iam, PasswordService passwords) {
        this.iam = iam;
        this.passwords = passwords;
    }

    @GetMapping("/users")
    List<IamService.User> users() {
        requireInstanceAdmin();
        return iam.allUsers();
    }

    /** The token is in this response and nowhere else. It lasts an hour and works once. */
    @PostMapping("/users/{userId}/password-reset")
    Map<String, Object> issueReset(@PathVariable UUID userId, HttpServletRequest request) {
        requireInstanceAdmin();
        PasswordService.Reset reset = passwords.issueReset(userId, CurrentUser.require().userId(),
                ClientIp.of(request));
        return Map.of("userId", reset.userId(), "email", reset.email(), "expiresAt", reset.expiresAt(),
                "token", reset.token(), "resetPath", "/reset-password?token=" + reset.token());
    }

    private static void requireInstanceAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiProblemException(401, "UNAUTHENTICATED", "Please log in");
        }
        boolean admin = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("INSTANCE_ADMIN"::equals);
        if (!admin) {
            throw new ForbiddenException("Only an instance administrator can do this");
        }
    }
}
