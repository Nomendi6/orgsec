package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Raised when the Redis control record is missing, malformed or non-canonical.
 */
final class RedisControlCorruptionException extends RedisProtocolException {

    /** Stable diagnostic code for health, logs and operational automation. */
    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_CONTROL_CORRUPT";

    /**
     * Creates a control-record corruption error.
     *
     * @param detail description that does not include authorization payload data
     */
    RedisControlCorruptionException(String detail) {
        super(DIAGNOSTIC_CODE + ": " + detail);
    }

    /**
     * Creates a control-record corruption error with its decoding cause.
     *
     * @param detail description that does not include authorization payload data
     * @param cause decoding cause
     */
    RedisControlCorruptionException(String detail, Throwable cause) {
        super(DIAGNOSTIC_CODE + ": " + detail, cause);
    }
}
