package com.aesoftwaresolutions.solid.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aesoftwaresolutions.solid.TestcontainersConfiguration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 018, AC 1, 2 and 5. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InstanceIdentityTests {

    @Autowired
    JdbcClient db;

    @Autowired
    InstanceIdentity identity;

    @Value("${solid.security.master-key}")
    String masterKey;

    @Test
    void ac1_oneRowIsWrittenOnceAndLeftAloneAfterwards() {
        UUID first = identity.instanceId();
        identity.verify();
        identity.verify();

        assertThat(db.sql("select count(*) from sys.instance").query(Long.class).single()).isEqualTo(1);
        assertThat(identity.instanceId()).isEqualTo(first);
        assertThat(db.sql("select master_key_fingerprint from sys.instance").query(String.class).single().trim())
                .isEqualTo(identity.fingerprint());
    }

    @Test
    void ac2_aDifferentKeyIsRefusedWithAnExplanationAndNoKeyMaterial() {
        identity.verify();
        String otherKey = Base64.getEncoder().encodeToString(new byte[32]);
        InstanceIdentity wrong = new InstanceIdentity(db, otherKey);

        assertThatThrownBy(wrong::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOLID_MASTER_KEY")
                .hasMessageContaining("docs/operations.md")
                .extracting(Throwable::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain(otherKey, masterKey, InstanceIdentity.fingerprintOf(otherKey));
    }

    @Test
    void ac5_theFingerprintIsStableOneWayAndKeySpecific() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        String other = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdeg".getBytes());

        assertThat(InstanceIdentity.fingerprintOf(key)).isEqualTo(InstanceIdentity.fingerprintOf(key));
        assertThat(InstanceIdentity.fingerprintOf(key)).isNotEqualTo(InstanceIdentity.fingerprintOf(other));
        assertThat(InstanceIdentity.fingerprintOf(key)).hasSize(64).doesNotContain(key);
        assertThat(identity.shortFingerprint()).hasSize(16).isEqualTo(identity.fingerprint().substring(0, 16));
    }
}
