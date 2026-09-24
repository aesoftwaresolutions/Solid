package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Spec 064. Whatever route is taken out, nothing of Solid's is left running.
 */
class DesktopShutdownTests {

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
