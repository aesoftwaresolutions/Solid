package com.aesoftwaresolutions.solid.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Spec 007, AC 3. */
class TotpTests {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Sha1TestVectors() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30, 8, "HmacSHA1")).isEqualTo("94287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30, 8, "HmacSHA1")).isEqualTo("07081804");
        assertThat(Totp.code(RFC_SECRET, 1111111111L / 30, 8, "HmacSHA1")).isEqualTo("14050471");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30, 8, "HmacSHA1")).isEqualTo("89005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30, 8, "HmacSHA1")).isEqualTo("69279037");
        assertThat(Totp.code(RFC_SECRET, 20000000000L / 30, 8, "HmacSHA1")).isEqualTo("65353130");
    }

    @Test
    void acceptsAdjacentStepsAndRejectsReplayAndOldCodes() {
        byte[] secret = Totp.newSecret();
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        long step = Totp.stepAt(now);

        assertThat(Totp.verify(secret, Totp.codeAt(secret, step), now, null)).isEqualTo(step);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step - 2), now, null)).isEqualTo(-1);
        assertThat(Totp.verify(secret, Totp.codeAt(secret, step), now, step)).as("replay").isEqualTo(-1);
        assertThat(Totp.verify(secret, "12345", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(secret, null, now, null)).isEqualTo(-1);
    }

    @Test
    void base32RoundTripsAndMatchesRfc4648() {
        assertThat(Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        byte[] secret = Totp.newSecret();
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
        assertThat(Totp.otpauthUri("Solid", "a@b.test", secret)).startsWith("otpauth://totp/Solid:a%40b.test?secret=");
    }
}
