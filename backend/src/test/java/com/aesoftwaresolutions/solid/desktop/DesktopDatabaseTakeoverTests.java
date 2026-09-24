package com.aesoftwaresolutions.solid.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 063. Taking over a data directory a previous copy left behind.
 *
 * <p>The dangerous half of this is the restraint: a pid file is just a number, and by the time Solid reads it
 * that number may belong to anything at all. These tests are mostly about what must <em>not</em> be killed.
 *
 * <p>Spec 064 moved this to the one way down, used both on the way out and on the way in.
 */
class DesktopDatabaseTakeoverTests {

    @TempDir
    Path dataDir;

    @Test
    void ac5_aProcessThatIsNotADatabaseIsLeftAlone() throws IOException {
        // This JVM's own pid, which is alive and is certainly not PostgreSQL. If the code were careless it
        // would kill the process running this test — so the test passing at all is most of the point.
        long me = ProcessHandle.current().pid();
        Files.writeString(dataDir.resolve("postmaster.pid"), me + "\n" + dataDir + "\n");

        DesktopShutdown.stopAnyServerStillRunning(dataDir);

        assertThat(ProcessHandle.current().isAlive()).isTrue();
    }

    @Test
    void ac5_aPidThatIsLongGoneIsIgnored() throws IOException {
        // A number no longer in use. Acting on it would mean killing whatever inherited it since.
        long unused = 4_000_000L;
        assertThat(ProcessHandle.of(unused)).as("pick a pid that really is free").isEmpty();
        Files.writeString(dataDir.resolve("postmaster.pid"), unused + "\n" + dataDir + "\n");

        assertThatCode(() -> DesktopShutdown.stopAnyServerStillRunning(dataDir)).doesNotThrowAnyException();
    }

    @Test
    void aMissingOrUnreadablePidFileIsNotAProblem() throws IOException {
        assertThatCode(() -> DesktopShutdown.stopAnyServerStillRunning(dataDir)).doesNotThrowAnyException();

        Files.writeString(dataDir.resolve("postmaster.pid"), "this is not a number\n");
        assertThatCode(() -> DesktopShutdown.stopAnyServerStillRunning(dataDir))
                .as("a damaged pid file is PostgreSQL's business, not a reason to fall over")
                .doesNotThrowAnyException();
    }
}
