package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec 064. Whatever route is taken out, nothing of Solid's is left running.
 */
class DesktopShutdownTests {

    /**
     * {@link DesktopShutdown} is process-wide. A desktop-profile test that ran earlier in this JVM leaves its
     * embedded PostgreSQL registered there, and Spring keeps that application context cached for the next
     * desktop test. Calling {@code everythingDown()} here used to stop that live database, so whichever desktop
     * test reused the context next failed with connection errors. Which one depended on the run order: on CI it
     * was DesktopModeTests. So the database is set aside for these tests and put back after.
     */
    private Object savedDatabase;
    private Object savedDataDir;

    @BeforeEach
    void setAsideAnyRunningDatabase() throws ReflectiveOperationException {
        savedDatabase = swap("database", null);
        savedDataDir = swap("dataDir", null);
    }

    @AfterEach
    void putItBack() throws ReflectiveOperationException {
        swap("database", savedDatabase);
        swap("dataDir", savedDataDir);
    }

    private static Object swap(String name, Object value) throws ReflectiveOperationException {
        Field field = DesktopShutdown.class.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, value);
        return previous;
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ac1_theWindowIsClosedWhenSolidStopsForAnyOtherReason() throws IOException, InterruptedException {
        // Stands in for the browser window: something that would happily outlive the application.
        Process window = new ProcessBuilder("sleep", "120").start();
        DesktopShutdown.remember(window);
        assertThat(window.isAlive()).isTrue();

        DesktopShutdown.everythingDown();

        assertThat(window.waitFor(10, TimeUnit.SECONDS))
                .as("a window left open in front of a server that has gone is worse than no window")
                .isTrue();
        assertThat(window.isAlive()).isFalse();
    }

    @Test
    void stoppingTwiceIsHarmless() {
        // Shutdown hooks and explicit quits can both arrive; the second must not throw or hang.
        DesktopShutdown.everythingDown();
        DesktopShutdown.everythingDown();
    }
}
