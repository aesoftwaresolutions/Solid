package com.aesoftwaresolutions.solid.docs;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decides a file's real type from its first bytes, so a script renamed to {@code receipt.pdf} is refused.
 * Only the types a bookkeeping vault needs are accepted.
 */
final class FileTypes {

    private FileTypes() {
    }

    static Optional<String> detect(byte[] bytes) {
        if (bytes.length < 4) {
            return Optional.empty();
        }
        if (startsWith(bytes, new byte[]{0x25, 0x50, 0x44, 0x46})) { // %PDF
            return Optional.of("application/pdf");
        }
        if (startsWith(bytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47})) {
            return Optional.of("image/png");
        }
        if (startsWith(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of("image/gif");
        }
        if (bytes.length > 12 && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        if (bytes.length > 12 && new String(bytes, 4, 8, StandardCharsets.US_ASCII).startsWith("ftyphei")) {
            return Optional.of("image/heic");
        }
        return textType(bytes);
    }

    /** CSV, OFX/QFX and plain text all arrive as text; reject anything with control characters or NUL bytes. */
    private static Optional<String> textType(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            boolean printable = b == 0x09 || b == 0x0A || b == 0x0D || (b >= 0x20 && b != 0x7F);
            if (!printable) {
                return Optional.empty();
            }
        }
        String head = new String(bytes, 0, sample, StandardCharsets.UTF_8).toUpperCase(java.util.Locale.ROOT);
        if (head.contains("OFXHEADER") || head.contains("<OFX>")) {
            return Optional.of("application/x-ofx");
        }
        if (head.contains(",") && head.contains("\n")) {
            return Optional.of("text/csv");
        }
        return Optional.of("text/plain");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
