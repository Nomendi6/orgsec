package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Raised when Redis is configured in a way that can evict authorization state.
 */
public final class RedisProtocolConfigurationException extends RedisProtocolException {

    /** Stable diagnostic code for unsafe Redis configuration. */
    public static final String DIAGNOSTIC_CODE = "ORGSEC_REDIS_PROTOCOL_CONFIGURATION";

    /**
     * Creates a configuration error.
     *
     * @param detail unsafe or indeterminate configuration detail
     */
    public RedisProtocolConfigurationException(String detail) {
        super(DIAGNOSTIC_CODE + ": " + detail);
    }
}
