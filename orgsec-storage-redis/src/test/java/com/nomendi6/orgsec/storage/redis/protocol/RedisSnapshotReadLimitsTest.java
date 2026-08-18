package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotReadLimitsTest {

    @Test
    void retainsExplicitQualifiedLimits() {
        RedisSnapshotReadLimits limits = new RedisSnapshotReadLimits(
            100,
            500,
            1024,
            10,
            20,
            30,
            50
        );

        assertThat(limits.maxEntriesPerFamily()).isEqualTo(100);
        assertThat(limits.maxTotalEntries()).isEqualTo(500);
        assertThat(limits.maxAccountedBytes()).isEqualTo(1024);
        assertThat(limits.pageEntries()).isEqualTo(10);
        assertThat(limits.pageKeyBytes()).isEqualTo(20);
        assertThat(limits.pagePayloadBytes()).isEqualTo(30);
        assertThat(limits.pageTotalBytes()).isEqualTo(50);
    }

    @Test
    void rejectsUnboundedOrInternallyImpossibleConfigurations() {
        assertThatThrownBy(() -> new RedisSnapshotReadLimits(0, 1, 1, 1, 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxEntriesPerFamily");
        assertThatThrownBy(() -> new RedisSnapshotReadLimits(1, 1, 1, 1025, 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pageEntries");
        assertThatThrownBy(() -> new RedisSnapshotReadLimits(1, 1, 1, 1, 2, 1, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pageKeyBytes");
        assertThatThrownBy(() -> new RedisSnapshotReadLimits(1, 1, 1, 1, 1, 2, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pagePayloadBytes");
    }

    @Test
    void scaffoldDefaultsAreFiniteAndCoverTheLargestCanonicalPayload() {
        RedisSnapshotReadLimits limits = RedisSnapshotReadLimits.scaffoldDefaults();

        assertThat(limits.pagePayloadBytes())
            .isEqualTo(RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES);
        assertThat(limits.pageTotalBytes()).isLessThanOrEqualTo(64 * 1024 * 1024);
        assertThat(limits.pageEntries()).isLessThanOrEqualTo(1024);
    }
}
