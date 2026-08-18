package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisControlEnvelopeTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void acceptsTheThreeCanonicalStateShapes() {
        RedisControlEnvelope initializing = envelope(0, null, RedisControlState.INITIALIZING);
        RedisControlEnvelope firstUpdate = envelope(0, null, RedisControlState.UPDATING);
        RedisControlEnvelope laterUpdate = envelope(1, SNAPSHOT_ID, RedisControlState.UPDATING);
        RedisControlEnvelope ready = envelope(1, SNAPSHOT_ID, RedisControlState.READY);

        assertThat(initializing.getIdentity()).isEqualTo(identity());
        assertThat(initializing.getIncarnation()).isEqualTo(incarnation());
        assertThat(initializing.getCounter()).isZero();
        assertThat(initializing.getActiveSnapshotId()).isNull();
        assertThat(initializing.getState()).isEqualTo(RedisControlState.INITIALIZING);
        assertThat(firstUpdate.getActiveSnapshotId()).isNull();
        assertThat(laterUpdate.getActiveSnapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(ready.getState()).isEqualTo(RedisControlState.READY);
        assertThat(envelope(
            RedisControlEnvelope.MAX_COUNTER,
            SNAPSHOT_ID,
            RedisControlState.READY
        ).getCounter()).isEqualTo(9_007_199_254_740_991L);
    }

    @Test
    void isAnImmutableValueOverEveryEnvelopeComponent() {
        RedisControlEnvelope baseline = envelope(1, SNAPSHOT_ID, RedisControlState.READY);
        RedisControlEnvelope equal = envelope(1, SNAPSHOT_ID, RedisControlState.READY);
        RedisControlEnvelope differentIdentity = new RedisControlEnvelope(
            new SecurityDatasetIdentity("tenant-b", 1),
            incarnation(),
            1,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
        RedisControlEnvelope differentIncarnation = new RedisControlEnvelope(
            identity(),
            new RedisIncarnation(
                RUN_ID,
                UUID.fromString("44444444-4444-4444-8444-444444444444")
            ),
            1,
            SNAPSHOT_ID,
            RedisControlState.READY
        );

        assertThat(baseline).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(baseline.equals(baseline)).isTrue();
        assertThat(baseline).isNotEqualTo(differentIdentity).isNotEqualTo(differentIncarnation);
        assertThat(baseline).isNotEqualTo(envelope(2, SNAPSHOT_ID, RedisControlState.READY));
        assertThat(baseline).isNotEqualTo(envelope(1, SNAPSHOT_ID, RedisControlState.UPDATING));
        assertThat(baseline).isNotEqualTo(envelope(
            1,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            RedisControlState.READY
        ));
        assertThat(baseline).isNotEqualTo(null).isNotEqualTo(identity());
        assertThat(baseline.toString())
            .contains("counter=1", SNAPSHOT_ID.toString(), "state=READY");
    }

    @Test
    void rejectsMissingComponentsNegativeCountersAndInvalidStateSnapshotShapes() {
        assertThatThrownBy(() -> new RedisControlEnvelope(
            null, incarnation(), 0, null, RedisControlState.INITIALIZING
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("identity");
        assertThatThrownBy(() -> new RedisControlEnvelope(
            identity(), null, 0, null, RedisControlState.INITIALIZING
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("incarnation");
        assertThatThrownBy(() -> new RedisControlEnvelope(
            identity(), incarnation(), -1, null, RedisControlState.INITIALIZING
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("counter");
        assertThatThrownBy(() -> new RedisControlEnvelope(
            identity(),
            incarnation(),
            RedisControlEnvelope.MAX_COUNTER + 1,
            SNAPSHOT_ID,
            RedisControlState.READY
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("counter", "Lua-safe");
        assertThatThrownBy(() -> new RedisControlEnvelope(
            identity(), incarnation(), 0, null, null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("state");
        assertThatThrownBy(() -> envelope(1, null, RedisControlState.READY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("READY");
        assertThatThrownBy(() -> envelope(0, SNAPSHOT_ID, RedisControlState.INITIALIZING))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INITIALIZING");
    }

    @Test
    void rejectsFutureProtocolIdentityWithASanitizedArgumentError() {
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);

        assertThat(futureIdentity.getProtocolVersion()).isEqualTo(2);
        assertThatThrownBy(() -> new RedisControlEnvelope(
            futureIdentity,
            incarnation(),
            0,
            null,
            RedisControlState.INITIALIZING
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    private static RedisControlEnvelope envelope(
        long counter,
        UUID snapshotId,
        RedisControlState state
    ) {
        return new RedisControlEnvelope(identity(), incarnation(), counter, snapshotId, state);
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("tenant-a", 1);
    }

    private static RedisIncarnation incarnation() {
        return new RedisIncarnation(RUN_ID, STORAGE_UUID);
    }
}
