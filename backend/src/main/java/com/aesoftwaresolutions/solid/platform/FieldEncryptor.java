package com.aesoftwaresolutions.solid.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts small secrets (MFA secrets now; SSNs/EINs/bank numbers later) with AES-256-GCM before they are
 * stored. Output format: base64( version(1) | iv(12) | ciphertext+tag ).
 *
 * <p>The 32-byte master key comes from {@code SOLID_MASTER_KEY} (base64). Losing it makes encrypted data
 * unrecoverable, so it must be backed up separately from the database.
 */
@Component
public class FieldEncryptor {

    private static final byte VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public FieldEncryptor(@Value("${solid.security.master-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("solid.security.master-key must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("solid.security.master-key must decode to exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(1 + iv.length + ct.length).put(VERSION).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            if (buf.get() != VERSION) {
                throw new IllegalStateException("Unknown ciphertext version");
            }
            byte[] iv = new byte[12];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }
}
