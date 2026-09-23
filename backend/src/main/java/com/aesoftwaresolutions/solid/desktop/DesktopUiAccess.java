package com.aesoftwaresolutions.solid.desktop;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * On a desktop install the application serves the web UI itself, so the browser must be able to fetch the
 * login screen before anyone has signed in (spec 060).
 *
 * <p>This opens up the UI's own files and the routes that render it — HTML, JavaScript, CSS, icons — and
 * nothing else. Every {@code /api/**} path stays exactly as protected as it is on a server, so a mistake here
 * cannot expose a single figure from the books.
 */
@Configuration(proxyBeanMethods = false)
@Profile("desktop")
class DesktopUiAccess {

    @Bean
    RequestMatcher publicUiMatcher() {
        return request -> {
            if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
                return false;
            }
            String path = request.getRequestURI();
            if (path.startsWith("/api/") || path.startsWith("/actuator/") || path.startsWith("/v3/")
                    || path.startsWith("/swagger")) {
                return false;
            }
            return true;
        };
    }
}
