package com.aesoftwaresolutions.solid.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTests {

    @Test
    void generatesVersion7VariantRfcUuids() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreUniqueAndTimeOrdered() throws InterruptedException {
        Set<UUID> seen = new HashSet<>();
        UUID previous = Ids.newId();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Ids.newId())).isTrue();
        }
        Thread.sleep(2);
        UUID later = Ids.newId();
        assertThat(later.getMostSignificantBits() >>> 16).isGreaterThan(previous.getMostSignificantBits() >>> 16);
    }
}
