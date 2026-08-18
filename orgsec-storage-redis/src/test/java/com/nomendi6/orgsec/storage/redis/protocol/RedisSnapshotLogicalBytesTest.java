package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotLogicalBytesTest {

    @Test
    void accountsExactUtf8KeyNamesAndHashPlusIndexEntryShape() {
        RedisCanonicalEntry entry = new RedisCanonicalEntry(
            "ø".getBytes(StandardCharsets.UTF_8),
            new byte[]{1, 2, 3}
        );

        long base = RedisSnapshotLogicalBytes.familyBase("rédis", "鍵");
        long entryBytes = RedisSnapshotLogicalBytes.entry(entry);

        assertThat(base).isEqualTo(9);
        assertThat(entryBytes).isEqualTo(7);
        assertThat(RedisSnapshotLogicalBytes.addExact(base, entryBytes)).isEqualTo(16);
    }

    @Test
    void rejectsMalformedUnicodeKeyNamesAndNegativeAccounting() {
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase("\ud800", "index"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dataKey");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase("data", "\udfff"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("indexKey");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase(null, "index"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("dataKey");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.addExact(-1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.addExact(0, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
    }

    @Test
    void rejectsEmptyOrCollidingHashAndIndexKeyNames() {
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase("", "index"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dataKey");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase("data", ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("indexKey");
        assertThatThrownBy(() -> RedisSnapshotLogicalBytes.familyBase("same", "same"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("distinct");

        assertThat(RedisSnapshotLogicalBytes.familyBase(" ", "  ")).isEqualTo(3);
    }
}
