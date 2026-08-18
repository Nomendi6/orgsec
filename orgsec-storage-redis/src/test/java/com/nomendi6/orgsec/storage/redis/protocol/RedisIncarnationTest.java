package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisIncarnationTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void isAnImmutableValueContainingPrimaryAndRandomStorageIdentities() {
        RedisIncarnation incarnation = new RedisIncarnation(RUN_ID, STORAGE_UUID);
        RedisIncarnation equal = new RedisIncarnation(RUN_ID, STORAGE_UUID);
        RedisIncarnation other = new RedisIncarnation(
            RUN_ID,
            UUID.fromString("33333333-3333-4333-8333-333333333333")
        );

        assertThat(incarnation.getPrimaryRunId()).isEqualTo(RUN_ID);
        assertThat(incarnation.getStorageUuid()).isEqualTo(STORAGE_UUID);
        assertThat(incarnation).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(incarnation.equals(incarnation)).isTrue();
        assertThat(incarnation).isNotEqualTo(other).isNotEqualTo(null).isNotEqualTo(RUN_ID);
        assertThat(incarnation.toString()).contains(RUN_ID, STORAGE_UUID.toString());
    }

    @Test
    void createGeneratesASeparateVersionFourUuid() {
        RedisIncarnation first = RedisIncarnation.create(RUN_ID);
        RedisIncarnation second = RedisIncarnation.create(RUN_ID);

        assertThat(first.getStorageUuid().version()).isEqualTo(4);
        assertThat(first.getStorageUuid().variant()).isEqualTo(2);
        assertThat(first.getStorageUuid()).isNotEqualTo(second.getStorageUuid());
    }

    @Test
    void rejectsNonCanonicalPrimaryRunIds() {
        assertThatThrownBy(() -> new RedisIncarnation(null, STORAGE_UUID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primaryRunId");
        assertThatThrownBy(() -> new RedisIncarnation(RUN_ID.toUpperCase(), STORAGE_UUID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primaryRunId");
        assertThatThrownBy(() -> new RedisIncarnation(RUN_ID.substring(1), STORAGE_UUID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primaryRunId");
        assertThatThrownBy(() -> new RedisIncarnation(RUN_ID.substring(0, 39) + "z", STORAGE_UUID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("primaryRunId");
    }

    @Test
    void rejectsMissingAndNonRandomStorageUuids() {
        assertThatThrownBy(() -> new RedisIncarnation(RUN_ID, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("storageUuid");
        assertThatThrownBy(() -> new RedisIncarnation(
            RUN_ID,
            UUID.fromString("22222222-2222-1222-8222-222222222222")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version-4");
        assertThatThrownBy(() -> new RedisIncarnation(
            RUN_ID,
            UUID.fromString("22222222-2222-4222-c222-222222222222")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version-4");
    }
}
