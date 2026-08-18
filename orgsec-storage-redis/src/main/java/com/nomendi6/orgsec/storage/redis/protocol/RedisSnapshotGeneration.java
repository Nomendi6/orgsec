package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identity of one authoritative READY snapshot generation observed on a Redis primary.
 *
 * <p>The generation retains the exact primary observation and control envelope used to create it.
 * It is therefore suitable as a comparison token for a final atomic recheck, but does not by
 * itself make manifest data trusted.</p>
 */
final class RedisSnapshotGeneration {

    private final RedisPrimaryObservation observation;
    private final RedisControlEnvelope controlEnvelope;

    private RedisSnapshotGeneration(
        RedisPrimaryObservation observation,
        RedisControlEnvelope controlEnvelope
    ) {
        this.observation = observation;
        this.controlEnvelope = controlEnvelope;
    }

    /**
     * Derives a generation only from an exact compatible READY primary observation.
     *
     * @param primarySnapshot atomic primary/control observation
     * @param expectedIdentity exact locally accepted dataset and protocol identity
     * @return immutable generation token
     */
    static RedisSnapshotGeneration from(
        RedisPrimarySnapshot primarySnapshot,
        SecurityDatasetIdentity expectedIdentity
    ) {
        Objects.requireNonNull(primarySnapshot, "primarySnapshot must not be null");
        Objects.requireNonNull(expectedIdentity, "expectedIdentity must not be null");
        RedisWireProtocol.requireVersionOne(expectedIdentity);

        RedisControlEnvelope envelope = primarySnapshot.controlEnvelope().orElseThrow(() ->
            new IllegalStateException("READY snapshot generation requires a control envelope")
        );
        if (envelope.getState() != RedisControlState.READY) {
            throw new IllegalStateException("snapshot generation requires READY control state");
        }
        if (envelope.getActiveSnapshotId() == null) {
            throw new IllegalStateException(
                "READY snapshot generation requires an active snapshot UUID"
            );
        }
        if (!expectedIdentity.equals(envelope.getIdentity())) {
            throw new IllegalArgumentException(
                "control identity must exactly match the expected dataset identity"
            );
        }

        RedisPrimaryObservation observation = primarySnapshot.observation();
        if (!observation.runId().equals(envelope.getIncarnation().getPrimaryRunId())) {
            throw new IllegalStateException(
                "observed primary run ID must match the control incarnation"
            );
        }
        return new RedisSnapshotGeneration(observation, envelope);
    }

    RedisPrimaryObservation observation() {
        return observation;
    }

    RedisControlEnvelope controlEnvelope() {
        return controlEnvelope;
    }

    SecurityDatasetIdentity identity() {
        return controlEnvelope.getIdentity();
    }

    RedisIncarnation incarnation() {
        return controlEnvelope.getIncarnation();
    }

    long counter() {
        return controlEnvelope.getCounter();
    }

    UUID activeSnapshotId() {
        return controlEnvelope.getActiveSnapshotId();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisSnapshotGeneration)) {
            return false;
        }
        RedisSnapshotGeneration that = (RedisSnapshotGeneration) other;
        return observation.equals(that.observation)
            && controlEnvelope.equals(that.controlEnvelope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(observation, controlEnvelope);
    }

    @Override
    public String toString() {
        SecurityDatasetIdentity identity = identity();
        return "RedisSnapshotGeneration{" +
            "datasetId='" + identity.getSecurityDatasetId() + '\'' +
            ", protocolVersion=" + identity.getProtocolVersion() +
            ", primaryRunId='" + incarnation().getPrimaryRunId() + '\'' +
            ", storageUuid=" + incarnation().getStorageUuid() +
            ", counter=" + counter() +
            ", activeSnapshotId=" + activeSnapshotId() +
            '}';
    }
}
