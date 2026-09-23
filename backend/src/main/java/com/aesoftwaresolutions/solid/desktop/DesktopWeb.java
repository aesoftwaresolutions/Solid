package com.aesoftwaresolutions.solid.desktop;

import java.awt.Desktop;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The two things a desktop install needs from the web layer that a server gets from Caddy: the single-page
 * app's fallback route, and someone to open the browser (spec 060).
 */
@Configuration(proxyBeanMethods = false)
@Profile("desktop")
class DesktopWeb implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DesktopWeb.class);

    private final boolean openBrowser;

    DesktopWeb(@Value("${solid.desktop.open-browser:true}") boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    /**
     * Every screen is a path the browser can be pointed at directly, and none of them are files, so anything
     * that is not the API and has no file extension is the app itself. Without this, refreshing on
     * /orgs/…/sales would 404.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{a:[^\\.]*}/{b:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{a:[^\\.]*}/{b:[^\\.]*}/{c:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/{a:[^\\.]*}/{b:[^\\.]*}/{c:[^\\.]*}/{d:[^\\.]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{a:[^\\.]*}/{b:[^\\.]*}/{c:[^\\.]*}/{d:[^\\.]*}/{e:[^\\.]*}")
                .setViewName("forward:/index.html");
    }

    @EventListener(ApplicationReadyEvent.class)
    void openTheBooks(ApplicationReadyEvent event) {
        int port = event.getApplicationContext() instanceof WebServerApplicationContext web
                && web.getWebServer() != null ? web.getWebServer().getPort() : DesktopSetup.PORT;
        URI url = URI.create("http://127.0.0.1:" + port + "/");
        log.info("Solid is running at {}", url);
        if (!openBrowser) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
                return;
            }
        } catch (Exception e) {
            log.debug("Could not open the browser through the desktop API", e);
        }
        // Headless Linux desktops often have no AWT Desktop; xdg-open is the usual way in.
        for (String opener : new String[]{"xdg-open", "open"}) {
            try {
                new ProcessBuilder(opener, url.toString()).start();
                return;
            } catch (Exception e) {
                log.debug("{} did not work", opener, e);
            }
        }
        log.info("Open {} in your browser to use Solid.", url);
    }
}
