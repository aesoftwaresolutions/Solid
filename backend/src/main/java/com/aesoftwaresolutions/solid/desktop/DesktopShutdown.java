package com.aesoftwaresolutions.solid.desktop;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closing Solid means nothing of Solid's is left running (spec 064).
 *
 * <p>There are more ways out of an application than closing its window, and each used to leave something
 * behind. Quitting any other way — a crash, Ctrl+C, signing out of Windows — left the window open in front of
 * a server that had gone. A database that refused to stop left processes holding the installed files, which is
 * what blocked an upgrade on the owner's machine. And a shutdown that hung left the whole thing alive with
 * nothing on screen at all.
 *
 * <p>So there is one way down, taken by one shutdown hook, whatever started it: close the window, stop the
 * database, make sure it really stopped, and — if any of that jams — stop waiting and halt.
 */
final class DesktopShutdown {

    private static final Logger log = LoggerFactory.getLogger(DesktopShutdown.class);

    /** Long enough for an honest shutdown, short enough that nobody watches a dead window wondering. */
    private static final long PATIENCE_SECONDS = 25;

    private static volatile Process window;
    private static volatile EmbeddedPostgres database;
    private static volatile Path dataDir;
    private static volatile boolean hookRegistered;

    private DesktopShutdown() {
    }

    /** The window, so that quitting by any other route takes it down too rather than leaving it stranded. */
    static synchronized void remember(Process windowProcess) {
        window = windowProcess;
        registerHook();
    }

    static synchronized void remember(EmbeddedPostgres postgres, Path directory) {
        database = postgres;
        dataDir = directory;
        registerHook();
    }

    private static void registerHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(DesktopShutdown::everythingDown, "solid-shutdown"));
    }

    /** Runs on the way out, however the way out was reached. */
    static void everythingDown() {
        Thread backstop = armTheBackstop();
        try {
            closeWindow();
            stopDatabase();
            stopAnyServerStillRunning(dataDir);
        } finally {
            // Shutting down finished, so the backstop stands down. Without this it would still be waiting to
            // halt the JVM — which is harmless on the way out and fatal anywhere else, as the test suite
            // demonstrated by having its own fork halted mid-run.
            backstop.interrupt();
        }
    }

    /**
     * If shutting down jams — a hung process, a wedged filesystem — stop waiting and end the JVM anyway.
     *
     * <p>A daemon thread, so it never keeps the application alive by itself; it only matters when something
     * else already refuses to finish. {@code halt} rather than {@code exit} because exit would queue behind
     * the very hook that is stuck.
     */
    private static Thread armTheBackstop() {
        Thread backstop = new Thread(() -> {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
                log.warn("Shutting down is taking too long; stopping now regardless.");
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "solid-shutdown-backstop");
        backstop.setDaemon(true);
        backstop.start();
        return backstop;
    }

    private static void closeWindow() {
        Process open = window;
        window = null;
        if (open == null || !open.isAlive()) {
            return;
        }
        log.info("Closing Solid's window.");
        open.destroy();
        try {
            if (!open.waitFor(5, TimeUnit.SECONDS)) {
                open.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            open.destroyForcibly();
        }
    }

    private static void stopDatabase() {
        EmbeddedPostgres running = database;
        database = null;
        if (running == null) {
            return;
        }
        try {
            // close() runs pg_ctl stop and waits for it.
            running.close();
            log.info("The bundled database has stopped.");
        } catch (IOException e) {
            log.warn("The bundled database did not stop cleanly; checking whether it is still there.", e);
        }
    }

    /**
     * Stops a PostgreSQL still holding a data directory — either one this run failed to stop, or one a
     * previous run left behind after being killed outright.
     *
     * <p>Used on the way out and again on the way in. The restraint matters as much as the action: a pid file
     * is only a number, and by the time it is read that number may belong to anything at all. So a process
     * that has gone is ignored, and a live one that is not a database is left strictly alone.
     */
    static void stopAnyServerStillRunning(Path directory) {
        stopAnyServerStillRunning(directory, null);
    }

    /**
     * @param earlyLog where to say what happened when this runs before logging exists (startup takeover);
     *                 null at shutdown, when the ordinary logger works
     */
    static void stopAnyServerStillRunning(Path directory, org.apache.commons.logging.Log earlyLog) {
        if (directory == null) {
            return;
        }
        Path pidFile = directory.resolve("postmaster.pid");
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            // The first line of postmaster.pid is the postmaster's process id; the rest is its own business.
            String first = Files.readAllLines(pidFile).stream().findFirst().orElse("").trim();
            long pid = Long.parseLong(first);
            Optional<ProcessHandle> running = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
            if (running.isEmpty()) {
                say(earlyLog, "The data directory names process " + pid + ", which is gone; carrying on.",
                        false);
                return;
            }
            String command = running.get().info().command().orElse("");
            if (!command.toLowerCase(Locale.ROOT).contains("postgres")) {
                say(earlyLog, "The data directory names process " + pid + " (" + command
                        + "), which is not a database; leaving it be.", true);
                return;
            }
            say(earlyLog, "A database from an earlier run is still holding " + directory + " (process "
                    + pid + "); stopping it.", true);
            ProcessHandle server = running.get();
            server.destroy();
            try {
                server.onExit().get(15, TimeUnit.SECONDS);
            } catch (Exception waited) {
                say(earlyLog, "It did not stop when asked; stopping it outright.", true);
                server.destroyForcibly();
            }
        } catch (NumberFormatException | IOException e) {
            say(earlyLog, "Could not read " + pidFile + "; letting PostgreSQL decide what to do about it.",
                    false);
        }
    }

    private static void say(org.apache.commons.logging.Log earlyLog, String message, boolean important) {
        if (earlyLog != null) {
            if (important) {
                earlyLog.info(message);
            } else {
                earlyLog.debug(message);
            }
        } else if (important) {
            log.info(message);
        } else {
            log.debug(message);
        }
    }
}
