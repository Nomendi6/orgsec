package com.nomendi6.orgsec.storage.redis.protocol;

/** Raised when a staging session no longer targets its exact primary/control generation. */
final class RedisStagingFenceException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_STAGING_FENCE_CHANGED";

    enum Reason {
        EXPECTATION_INCONSISTENT,
        GENERATION_CHANGED,
        LEASE_CHANGED
    }

    private final Reason reason;

    RedisStagingFenceException(Reason reason) {
        super(DIAGNOSTIC_CODE + ": " + requireReason(reason).name());
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    private static Reason requireReason(Reason reason) {
        if (reason == null) {
            throw new NullPointerException("reason must not be null");
        }
        return reason;
    }
}
