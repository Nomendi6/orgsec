package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authoritative control record for one Redis security dataset.
 */
final class RedisControlEnvelope {

    /** Largest integer that Redis Lua can compare and increment without IEEE-754 rounding. */
    static final long MAX_COUNTER = 9_007_199_254_740_991L;

    private final SecurityDatasetIdentity identity;
    private final RedisIncarnation incarnation;
    private final long counter;
    private final UUID activeSnapshotId;
    private final RedisControlState state;

    /**
     * Creates a control envelope.
     *
     * <p>A READY envelope always names a complete active snapshot. INITIALIZING must not name one.
     * UPDATING may retain the preceding active snapshot or be empty during first publication and
     * compatibility cutover. Reads remain fail-closed in either UPDATING form.</p>
     *
     * @param identity dataset and storage-protocol identity
     * @param incarnation physical-primary and storage incarnation identity
     * @param counter non-negative Redis Lua-safe publication counter within the incarnation
     * @param activeSnapshotId active snapshot UUID, subject to the state rules above
     * @param state publication state
     */
    RedisControlEnvelope(
        SecurityDatasetIdentity identity,
        RedisIncarnation incarnation,
        long counter,
        UUID activeSnapshotId,
        RedisControlState state
    ) {
        this.identity = RedisWireProtocol.requireVersionOne(identity);
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation must not be null");
        if (counter < 0 || counter > MAX_COUNTER) {
            throw new IllegalArgumentException(
                "counter must be between zero and the Redis Lua-safe maximum"
            );
        }
        this.state = Objects.requireNonNull(state, "state must not be null");
        if (state == RedisControlState.READY && activeSnapshotId == null) {
            throw new IllegalArgumentException("READY requires an activeSnapshotId");
        }
        if (state == RedisControlState.INITIALIZING && activeSnapshotId != null) {
            throw new IllegalArgumentException("INITIALIZING must not have an activeSnapshotId");
        }
        this.counter = counter;
        this.activeSnapshotId = activeSnapshotId;
    }

    SecurityDatasetIdentity getIdentity() {
        return identity;
    }

    RedisIncarnation getIncarnation() {
        return incarnation;
    }

    long getCounter() {
        return counter;
    }

    UUID getActiveSnapshotId() {
        return activeSnapshotId;
    }

    RedisControlState getState() {
        return state;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisControlEnvelope)) {
            return false;
        }
        RedisControlEnvelope that = (RedisControlEnvelope) other;
        return counter == that.counter
            && identity.equals(that.identity)
            && incarnation.equals(that.incarnation)
            && Objects.equals(activeSnapshotId, that.activeSnapshotId)
            && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, incarnation, counter, activeSnapshotId, state);
    }

    @Override
    public String toString() {
        return "RedisControlEnvelope{" +
            "identity=" + identity +
            ", incarnation=" + incarnation +
            ", counter=" + counter +
            ", activeSnapshotId=" + activeSnapshotId +
            ", state=" + state +
            '}';
    }
}
