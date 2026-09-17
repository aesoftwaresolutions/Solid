package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.common.ApiProblemException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the logged-in user from controllers. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<SolidPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SolidPrincipal p ? Optional.of(p) : Optional.empty();
    }

    public static SolidPrincipal require() {
        return find().orElseThrow(() -> new ApiProblemException(401, "UNAUTHENTICATED", "Please log in"));
    }
}
