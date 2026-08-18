package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One atomic, read-only structural observation of control, lease and lease-counter metadata.
 *
 * <p>This value carries no bootstrap, recovery, rebind or lease authority. In particular, an
 * absent triplet does not prove first boot, and a coherent triplet does not prove that it is current
 * rather than a restored or rolled-back copy.</p>
 */
final class RedisBootstrapTripletObservation {

    private final RedisDatasetKeyspace requestedKeyspace;
    private final RedisPrimaryObservation primary;
    private final RedisControlEnvelope control;
    private final RedisCoordinatorLease.Unverified lease;
    private final Long leaseCounter;

    RedisBootstrapTripletObservation(
        RedisDatasetKeyspace requestedKeyspace,
        RedisPrimaryObservation primary,
        RedisControlEnvelope control,
        RedisCoordinatorLease.Unverified lease,
        Long leaseCounter
    ) {
        this.requestedKeyspace = Objects.requireNonNull(
            requestedKeyspace,
            "requestedKeyspace must not be null"
        );
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        if (leaseCounter != null
            && (leaseCounter < 0
                || leaseCounter > RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER)) {
            throw new IllegalArgumentException(
                "leaseCounter must be between zero and the Redis Lua-safe maximum"
            );
        }
        this.control = control;
        this.lease = lease;
        this.leaseCounter = leaseCounter;
    }

    RedisDatasetKeyspace requestedKeyspace() {
        return requestedKeyspace;
    }

    RedisPrimaryObservation primary() {
        return primary;
    }

    Optional<RedisControlEnvelope> control() {
        return Optional.ofNullable(control);
    }

    Optional<RedisCoordinatorLease.Unverified> lease() {
        return Optional.ofNullable(lease);
    }

    OptionalLong leaseCounter() {
        return leaseCounter == null ? OptionalLong.empty() : OptionalLong.of(leaseCounter);
    }

    /** Never render dataset identity, incarnation or accounting values into diagnostics. */
    @Override
    public String toString() {
        return "RedisBootstrapTripletObservation{" +
            "controlPresent=" + (control != null) +
            ", leasePresent=" + (lease != null) +
            ", counterPresent=" + (leaseCounter != null) +
            '}';
    }
}
