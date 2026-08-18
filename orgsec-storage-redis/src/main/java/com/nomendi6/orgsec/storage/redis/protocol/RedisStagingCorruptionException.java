package com.nomendi6.orgsec.storage.redis.protocol;

/** Raised when immutable staging data or its manifest violates the append-only protocol. */
final class RedisStagingCorruptionException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_STAGING_CORRUPT";

    enum Reason {
        MANIFEST_PRESENT_DURING_APPEND,
        ENTRY_HALF_PAIR,
        ENTRY_PAYLOAD_MISMATCH,
        ENTRY_SCORE_NOT_ZERO,
        DUPLICATE_BATCH_KEY,
        FAMILY_COUNT_MISMATCH,
        MANIFEST_MISMATCH,
        ROLLBACK_FAILED,
        INVALID_SCRIPT_RESPONSE
    }

    private final Reason reason;

    RedisStagingCorruptionException(Reason reason) {
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
