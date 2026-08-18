package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Raised when a strict protocol operation cannot obtain an authoritative Redis result.
 */
public final class RedisProtocolUnavailableException extends RedisProtocolException {

    /** Stable diagnostic code for unavailable strict Redis operations. */
    public static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_PROTOCOL_UNAVAILABLE";

    /**
     * Creates an unavailable error.
     *
     * @param detail failed operation detail
     */
    public RedisProtocolUnavailableException(String detail) {
        super(DIAGNOSTIC_CODE + ": " + detail);
    }

    /**
     * Creates an unavailable error caused by a lower-level failure.
     *
     * @param detail failed operation detail
     * @param cause lower-level cause
     */
    public RedisProtocolUnavailableException(String detail, Throwable cause) {
        super(DIAGNOSTIC_CODE + ": " + detail, cause);
    }
}
