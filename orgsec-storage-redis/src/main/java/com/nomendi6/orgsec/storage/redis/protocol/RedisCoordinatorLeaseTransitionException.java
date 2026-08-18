package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/** Fail-closed outcome from a standalone coordinator-lease transition. */
final class RedisCoordinatorLeaseTransitionException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_LEASE_TRANSITION_FAILED";

    enum Reason {
        INVALID_REQUEST,
        TOPOLOGY_CHANGED,
        CONTROL_CHANGED,
        LEASE_CORRUPT,
        LEASE_LOST,
        COUNTER_EXHAUSTED,
        REVISION_EXHAUSTED,
        CLOCK_REGRESSED,
        INVALID_SCRIPT_RESPONSE
    }

    private final Reason reason;

    RedisCoordinatorLeaseTransitionException(Reason reason) {
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
