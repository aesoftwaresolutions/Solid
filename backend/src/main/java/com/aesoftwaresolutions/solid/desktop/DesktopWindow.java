package com.aesoftwaresolutions.solid.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Solid's own window (spec 063).
 *
 * <p>A chromeless window — no address bar, no tabs, its own profile folder — drawn by whichever
 * Chromium-family browser the machine already has. It is still the same screens over the same loopback
 * address; what changes is that nobody has to know that.
 *
 * <p>No browser engine is bundled to achieve this. JavaFX's WebView is GPL with the classpath exception and
 * this project takes only permissive dependencies; embedding Chromium would add a hundred megabytes and a
 * second thing to keep patched, for a window that would look no different.
 *
 * <p>When the window closes, Solid closes. That makes shutting down an ordinary event rather than a kill,
 * which is what gives the bundled database a chance to stop cleanly.
 */
final class DesktopWindow {

    private static final Logger log = LoggerFactory.getLogger(DesktopWindow.class);

    /** Under this, a window that has exited badly never really opened, so Solid stays up and falls back. */
    private static final long FAILED_TO_OPEN_MS = 5_000;

    private DesktopWindow() {
    }

    /** Opens the window, or the default browser, or nothing — whichever the settings and the machine allow. */
    static void open(ConfigurableApplicationContext context, Path appDir, URI url, String mode) {
        if (mode.equals("none")) {
            log.info("Solid is running at {} (no window was asked for)", url);
            return;
        }
        if (mode.equals("auto")) {
            Optional<Path> browser = findBrowser();
            if (browser.isPresent()) {
                openWindow(context, appDir, url, browser.get());
                return;
            }
            log.info("No Chromium-family browser found, so opening the default browser instead.");
        }
        openDefaultBrowser(url);
    }

    private static void openWindow(ConfigurableApplicationContext context, Path appDir, URI url, Path browser) {
        // A profile of Solid's own: none of the person's extensions, sign-ins or history, and the window
        // shows up as its own thing rather than another tab of theirs.
        Path profile = appDir.resolve("window");
        List<String> command = new ArrayList<>(List.of(
                browser.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--window-size=1280,900",
                "--no-first-run",
                "--no-default-browser-check"));
        try {
            Files.createDirectories(profile);
            long startedAt = System.currentTimeMillis();
            Process window = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            log.info("Solid is open in its own window ({})", browser.getFileName());
            // So that quitting any other way — a crash, Ctrl+C, signing out — takes the window with it
            // rather than leaving it in front of a server that has gone (spec 064).
            DesktopShutdown.remember(window);

            // Closing the window puts Solid away, which is what a person expects of an application.
            Thread watcher = new Thread(() -> {
                try {
                    int code = window.waitFor();
                    long lasted = System.currentTimeMillis() - startedAt;
                    // A window that dies at once never opened — no display, a crash, a locked profile. That
                    // is a reason to fall back, not to quit: quitting would leave the person with an
                    // application that closes the moment they start it.
                    if (code != 0 && lasted < FAILED_TO_OPEN_MS) {
                        log.warn("The window closed immediately (exit code {}); opening the default browser "
                                + "instead and leaving Solid running.", code);
                        openDefaultBrowser(url);
                        return;
                    }
                    log.info("The window was closed; stopping Solid.");
                    // Through Spring, so the shutdown hooks run and the bundled database stops properly.
                    System.exit(SpringApplication.exit(context, () -> 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "solid-window-watcher");
            watcher.setDaemon(false);
            watcher.start();
        } catch (IOException e) {
            log.warn("Could not open the window with {}; falling back to the default browser.", browser, e);
            openDefaultBrowser(url);
        }
    }

    private static void openDefaultBrowser(URI url) {
        log.info("Solid is running at {}", url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
                return;
            }
        } catch (Exception e) {
            log.debug("The desktop API could not open a browser", e);
        }
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

    /**
     * The first Chromium-family browser this machine has. Edge is on every Windows; Chrome is on most of
     * everything else. Only the program is used — never the person's profile.
     */
    static Optional<Path> findBrowser() {
        for (String candidate : candidates()) {
            Path path = Path.of(candidate);
            if (path.isAbsolute()) {
                if (Files.isExecutable(path)) {
                    return Optional.of(path);
                }
            } else {
                Optional<Path> onPath = onPath(candidate);
                if (onPath.isPresent()) {
                    return onPath;
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String programFiles = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
            String programFilesX86 = System.getenv().getOrDefault("ProgramFiles(x86)",
                    "C:\\Program Files (x86)");
            String localAppData = System.getenv().getOrDefault("LOCALAPPDATA",
                    System.getProperty("user.home") + "\\AppData\\Local");
            return List.of(
                    programFilesX86 + "\\Microsoft\\Edge\\Application\\msedge.exe",
                    programFiles + "\\Microsoft\\Edge\\Application\\msedge.exe",
                    programFiles + "\\Google\\Chrome\\Application\\chrome.exe",
                    programFilesX86 + "\\Google\\Chrome\\Application\\chrome.exe",
                    localAppData + "\\Google\\Chrome\\Application\\chrome.exe",
                    programFiles + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
        }
        if (os.contains("mac")) {
            return List.of(
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium");
        }
        return List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                "microsoft-edge", "brave-browser");
    }

    private static Optional<Path> onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory, program);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
