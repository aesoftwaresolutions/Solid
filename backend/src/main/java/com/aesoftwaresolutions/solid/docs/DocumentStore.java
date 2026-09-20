package com.aesoftwaresolutions.solid.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded files on the instance's disk, encrypted with AES-256-GCM using the instance master key.
 * File layout: {@code <root>/<orgId>/<yyyy>/<mm>/<documentId>} — never the original filename.
 */
@Component
class DocumentStore {

    /** Version 2 binds the ciphertext to its storage key; version 1 files (no AAD) are still readable. */
    private static final byte VERSION = 2;
    private static final byte VERSION_WITHOUT_AAD = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final SecretKey key;

    DocumentStore(@Value("${solid.documents.root:./data/documents}") String root,
                  @Value("${solid.security.master-key}") String base64Key) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("solid.security.master-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    String store(UUID orgId, UUID documentId, java.time.LocalDate date, byte[] plaintext) {
        String storageKey = orgId + "/" + date.getYear() + "/" + String.format("%02d", date.getMonthValue())
                + "/" + documentId;
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, encrypt(plaintext, storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store document", e);
        }
        return storageKey;
    }

    byte[] read(String storageKey) {
        try {
            return decrypt(Files.readAllBytes(resolve(storageKey)), storageKey);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document", e);
        }
    }

    void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete document", e);
        }
    }

    Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    private byte[] encrypt(byte[] plaintext, String storageKey) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            // The storage key is authenticated but not encrypted: a file moved to another document's path (or
            // restored from another instance's backup) then fails to decrypt instead of being served as that
            // document's contents.
            cipher.updateAAD(storageKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + iv.length + ciphertext.length).put(VERSION).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] stored, String storageKey) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            byte version = buffer.get();
            if (version != VERSION && version != VERSION_WITHOUT_AAD) {
                throw new IllegalStateException("Unknown document format");
            }
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            if (version == VERSION) {
                cipher.updateAAD(storageKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Could not decrypt document (wrong key, wrong file in this place, or tampered file)", e);
        }
    }
}
