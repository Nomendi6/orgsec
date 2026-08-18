package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/** Raised when a read-only bootstrap metadata observation is malformed or non-canonical. */
final class RedisBootstrapTripletCorruptionException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_BOOTSTRAP_TRIPLET_CORRUPT";

    enum Reason {
        RESULT_SHAPE_INVALID,
        INFO_BOUNDS_INVALID,
        INFO_WIRE_INVALID,
        TOPOLOGY_INVALID,
        CONFIGURATION_INVALID,
        CONTROL_TYPE_INVALID,
        CONTROL_TTL_INVALID,
        CONTROL_WIRE_INVALID,
        LEASE_TYPE_INVALID,
        LEASE_TTL_INVALID,
        LEASE_WIRE_INVALID,
        COUNTER_TYPE_INVALID,
        COUNTER_TTL_INVALID,
        COUNTER_WIRE_INVALID
    }

    private final Reason reason;

    RedisBootstrapTripletCorruptionException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return DIAGNOSTIC_CODE + ":" + Objects.requireNonNull(
            reason,
            "reason must not be null"
        ).name();
    }
}
