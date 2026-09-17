package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class FieldEncryptorTests {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());

    @Test
    void roundTripsAndUsesRandomIv() {
        FieldEncryptor enc = new FieldEncryptor(KEY_A);
        String c1 = enc.encrypt("JBSWY3DPEHPK3PXP");
        String c2 = enc.encrypt("JBSWY3DPEHPK3PXP");
        assertThat(c1).isNotEqualTo(c2).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(enc.decrypt(c1)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void wrongKeyOrTamperingFails() {
        String c = new FieldEncryptor(KEY_A).encrypt("secret");
        assertThatThrownBy(() -> new FieldEncryptor(KEY_B).decrypt(c)).isInstanceOf(IllegalStateException.class);
        byte[] raw = Base64.getDecoder().decode(c);
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> new FieldEncryptor(KEY_A).decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadKeys() {
        assertThatThrownBy(() -> new FieldEncryptor(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldEncryptor("not base64!!")).isInstanceOf(IllegalStateException.class);
    }
}
