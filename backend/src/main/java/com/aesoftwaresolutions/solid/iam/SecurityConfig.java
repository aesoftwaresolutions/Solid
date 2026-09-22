package com.aesoftwaresolutions.solid.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, IamService iam, ObjectMapper mapper) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // always load token so the XSRF-TOKEN cookie is issued

        // CSRF only matters when the browser sends the session cookie automatically. Requests authenticated with
        // a bearer header, or carrying no session cookie at all, can't be forged this way.
        RequestMatcher needsCsrf = request -> {
            String method = request.getMethod();
            boolean unsafe = !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
            String auth = request.getHeader("Authorization");
            boolean bearer = auth != null && auth.startsWith("Bearer ");
            boolean hasSessionCookie = request.getCookies() != null && java.util.Arrays.stream(request.getCookies())
                    .anyMatch(c -> SessionAuthenticationFilter.COOKIE.equals(c.getName()));
            String path = request.getRequestURI();
            boolean anonymousEndpoint = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/signup");
            return unsafe && !bearer && hasSessionCookie && !anonymousEndpoint;
        };

        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        .requireCsrfProtectionMatcher(needsCsrf))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .addFilterBefore(new SessionAuthenticationFilter(iam), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login",
                                // Accepting an invitation is how someone without an account gets one.
                                "/api/v1/auth/accept-invitation",
                                // Spending a reset token: the person cannot sign in, which is the point.
                                "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/v1/auth/mfa/**", "/api/v1/auth/logout", "/api/v1/auth/me")
                        .hasAnyAuthority("USER", "MFA_PENDING")
                        .requestMatchers("/api/**").hasAuthority("USER")
                        // The API description is not public: it maps out one household's or business's server.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasAuthority("USER")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, mapper, 401, "UNAUTHENTICATED", "Please log in"))
                        .accessDeniedHandler((request, response, ex) -> {
                            boolean pendingMfa = CurrentUser.find().map(p -> !p.mfaVerified()).orElse(false);
                            if (pendingMfa) {
                                writeProblem(response, mapper, 401, "MFA_REQUIRED", "Complete two-factor authentication first");
                            } else {
                                writeProblem(response, mapper, 403, "FORBIDDEN", "Request refused (missing or invalid CSRF token?)");
                            }
                        }));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper mapper, int status, String code,
                                     String detail) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("status", status, "code", code, "detail", detail,
                "title", status == 401 ? "Unauthorized" : "Forbidden"));
    }
}
