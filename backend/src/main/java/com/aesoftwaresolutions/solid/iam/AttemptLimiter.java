package com.aesoftwaresolutions.solid.iam;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits how often one address may try a secret: a password, a sign-in code, an invitation or reset link
 * (spec 065, row 7).
 *
 * <p>Each of those endpoints deliberately runs a slow password hash — even for an email that does not exist,
 * so that timing gives nothing away. Without a limit, that care turns into a lever: anyone could make the server
 * hash as fast as they can send requests. The per-account lockout does not help there, because it only starts
 * once an account is found.
 *
 * <p>A fixed one-minute window per address, kept in memory. That is deliberately simple: it resets on restart
 * and is per server, which is fine for something whose job is to take the edge off a flood, not to count
 * precisely. The address is the one Tomcat settles on after trusted proxies (see {@code application.yml}), so
 * a caller cannot dodge the limit by inventing a new {@code X-Forwarded-For} on every request.
 */
@Configuration(proxyBeanMethods = false)
class AttemptLimiter {

    /** Every endpoint that checks something secret, or hashes a password to create one. */
    private static final Set<String> GUARDED = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/mfa/verify",
            "/api/v1/auth/mfa/activate",
            "/api/v1/auth/accept-invitation",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/change-password");

    /** Beyond this many addresses in the table, finished windows are cleared out before counting on. */
    private static final int TIDY_ABOVE = 10_000;

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> attemptLimitFilter(
            @Value("${solid.auth.attempts-per-minute:20}") int perMinute, Clock clock) {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(new Limit(Math.max(1, perMinute), clock));
        registration.addUrlPatterns("/api/v1/auth/*");
        // Before Spring Security, so a flood is turned away before any of it reaches a password hash.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    static final class Limit extends OncePerRequestFilter {

        private record Window(long minute, int count) {
        }

        private final int perMinute;
        private final Clock clock;
        private final Map<String, Window> windows = new ConcurrentHashMap<>();

        Limit(int perMinute, Clock clock) {
            this.perMinute = perMinute;
            this.clock = clock;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !"POST".equals(request.getMethod()) || !GUARDED.contains(request.getRequestURI());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            long minute = clock.millis() / 60_000;
            if (windows.size() > TIDY_ABOVE) {
                windows.values().removeIf(w -> w.minute() < minute);
            }
            String address = String.valueOf(request.getRemoteAddr());
            Window now = windows.merge(address, new Window(minute, 1),
                    (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
            if (now.count() > perMinute) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many attempts\","
                        + "\"status\":429,\"code\":\"TOO_MANY_ATTEMPTS\","
                        + "\"detail\":\"Too many attempts from this address. Wait a minute and try again.\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
