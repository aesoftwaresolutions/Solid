package com.aesoftwaresolutions.solid.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Tamper evidence for posted entries. Each posted entry's hash covers its own content plus the previous
 * posted entry's hash, so changing any historical row breaks every hash after it.
 */
final class JournalHasher {

    static final String GENESIS = "0".repeat(64);

    record HashLine(int lineNo, UUID accountId, long amountMinor, String currency) {
    }

    private JournalHasher() {
    }

    static String hash(String prevHash, long seq, UUID entryId, String entryDate, String memo, List<HashLine> lines) {
        StringBuilder canonical = new StringBuilder()
                .append(prevHash).append('|')
                .append(seq).append('|')
                .append(entryId).append('|')
                .append(entryDate).append('|')
                .append(memo == null ? "" : memo.replace("|", "\\|"));
        lines.stream()
                .sorted((a, b) -> Integer.compare(a.lineNo(), b.lineNo()))
                .forEach(l -> canonical.append('|').append(l.lineNo()).append(':').append(l.accountId())
                        .append(':').append(l.amountMinor()).append(':').append(l.currency()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
