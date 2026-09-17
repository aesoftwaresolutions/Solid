package com.aesoftwaresolutions.solid.iam;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238), compatible with Google Authenticator, 1Password, Authy, etc.
 * 6 digits, 30-second steps, HMAC-SHA1.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long stepAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
    }

    public static String codeAt(byte[] secret, long step) {
        return code(secret, step, DIGITS, "HmacSHA1");
    }

    static String code(byte[] secret, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the matching step if {@code code} is valid at {@code now} ±1 step and newer than
     * {@code lastUsedStep} (prevents replay), otherwise -1.
     */
    public static long verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long current = stepAt(now);
        for (long step = current - 1; step <= current + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (java.security.MessageDigest.isEqual(codeAt(secret, step).getBytes(), code.getBytes())) {
                return step;
            }
        }
        return -1;
    }

    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
