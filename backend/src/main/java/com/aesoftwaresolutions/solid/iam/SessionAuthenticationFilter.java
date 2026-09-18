package com.aesoftwaresolutions.solid.iam;

import com.aesoftwaresolutions.solid.platform.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the session token from {@code Authorization: Bearer ...} or the {@code solid_session} cookie and, if
 * valid, marks the request as authenticated. Sessions that haven't passed MFA get only the
 * {@code MFA_PENDING} authority, which is enough for the MFA endpoints and nothing else.
 */
class SessionAuthenticationFilter extends OncePerRequestFilter {

    static final String COOKIE = "solid_session";

    private final IamService iam;

    SessionAuthenticationFilter(IamService iam) {
        this.iam = iam;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Optional<SolidPrincipal> principal = resolve(request);
            principal.ifPresent(p -> {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority(p.mfaVerified() ? "USER" : "MFA_PENDING"));
                if (p.mfaVerified() && p.instanceAdmin()) {
                    authorities.add(new SimpleGrantedAuthority("INSTANCE_ADMIN"));
                }
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        p, null, authorities));
                RequestContext.set(new RequestContext.Caller(p.userId(), ClientIp.of(request)));
            });
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private Optional<SolidPrincipal> resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return iam.authenticate(header.substring(7).trim(), true);
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    return iam.authenticate(cookie.getValue(), false);
                }
            }
        }
        return Optional.empty();
    }
}
