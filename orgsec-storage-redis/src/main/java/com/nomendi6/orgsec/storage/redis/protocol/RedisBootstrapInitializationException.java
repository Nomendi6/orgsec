package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/** Fail-closed outcome from the first atomic Redis bootstrap initialization. */
final class RedisBootstrapInitializationException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE =
        "ORGSEC_STORAGE_REDIS_BOOTSTRAP_INITIALIZATION_FAILED";

    enum Reason {
        INVALID_REQUEST,
        INFO_BOUNDS_INVALID,
        TOPOLOGY_CHANGED,
        CONFIGURATION_INVALID,
        PARTIAL_METADATA,
        CONTROL_TYPE_INVALID,
        CONTROL_TTL_INVALID,
        CONTROL_WIRE_INVALID,
        LEASE_TYPE_INVALID,
        LEASE_TTL_INVALID,
        LEASE_WIRE_INVALID,
        COUNTER_TYPE_INVALID,
        COUNTER_TTL_INVALID,
        COUNTER_WIRE_INVALID,
        WRITE_FAILED_ROLLED_BACK,
        ROLLBACK_FAILED,
        INVALID_SCRIPT_RESPONSE
    }

    private final Reason reason;

    RedisBootstrapInitializationException(Reason reason) {
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
