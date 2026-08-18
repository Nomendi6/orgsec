package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * One atomic observation of the Redis primary and its dataset control record.
 */
final class RedisPrimarySnapshot {

    private final RedisDatasetKeyspace requestedKeyspace;
    private final RedisPrimaryObservation observation;
    private final RedisControlEnvelope controlEnvelope;

    /** Creates a decoded but not yet request-bound primary snapshot. */
    RedisPrimarySnapshot(
        RedisPrimaryObservation observation,
        RedisControlEnvelope controlEnvelope
    ) {
        this(null, observation, controlEnvelope);
    }

    /** Creates a primary snapshot carrying the trusted namespace requested by its caller. */
    RedisPrimarySnapshot(
        RedisDatasetKeyspace requestedKeyspace,
        RedisPrimaryObservation observation,
        RedisControlEnvelope controlEnvelope
    ) {
        this.requestedKeyspace = requestedKeyspace;
        this.observation = Objects.requireNonNull(observation, "observation must not be null");
        this.controlEnvelope = controlEnvelope;
    }

    /**
     * Binds a decoded snapshot to the trusted keyspace that selected its control key.
     *
     * <p>A present control record must name that exact dataset. The comparison includes both the
     * original identifier and its canonical keyspace hash so later mutation code never needs to
     * derive keys from decoded Redis metadata.</p>
     */
    RedisPrimarySnapshot bindRequestedKeyspace(RedisDatasetKeyspace keyspace) {
        Objects.requireNonNull(keyspace, "keyspace must not be null");
        if (requestedKeyspace != null && !sameKeyspace(requestedKeyspace, keyspace)) {
            throw corrupt("primary snapshot is already bound to a different requested keyspace");
        }
        if (controlEnvelope != null) {
            String controlDatasetId = controlEnvelope.getIdentity().getSecurityDatasetId();
            if (!keyspace.matchesSecurityDatasetId(controlDatasetId)) {
                throw corrupt("control dataset identity does not match the requested keyspace");
            }
        }
        if (requestedKeyspace != null) {
            return this;
        }
        return new RedisPrimarySnapshot(keyspace, observation, controlEnvelope);
    }

    Optional<RedisDatasetKeyspace> requestedKeyspace() {
        return Optional.ofNullable(requestedKeyspace);
    }

    RedisPrimaryObservation observation() {
        return observation;
    }

    Optional<RedisControlEnvelope> controlEnvelope() {
        return Optional.ofNullable(controlEnvelope);
    }

    private static boolean sameKeyspace(
        RedisDatasetKeyspace first,
        RedisDatasetKeyspace second
    ) {
        return first.matchesSecurityDatasetId(second.securityDatasetId())
            && first.datasetHash().equals(second.datasetHash());
    }

    private static RedisControlCorruptionException corrupt(String detail) {
        return new RedisControlCorruptionException(detail);
    }
}
